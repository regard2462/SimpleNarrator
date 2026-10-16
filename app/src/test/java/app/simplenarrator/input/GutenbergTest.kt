package app.simplenarrator.input

import app.simplenarrator.testutil.TinyHttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Gutendex-Client gegen einen lokalen Testserver mit Beispiel-JSON und einem kleinen EPUB. */
class GutenbergTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String, base: String): String =
        requireNotNull(javaClass.getResource("/gutendex/$name")).readText().replace("{{BASE}}", base)

    private val fakeEpub: ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray()); z.closeEntry()
            // Zufallsdaten lassen sich nicht komprimieren, so kommt die Datei in mehreren Stücken an
            z.putNextEntry(ZipEntry("OEBPS/noise.bin")); z.write(ByteArray(300_000).also { java.util.Random(1).nextBytes(it) }); z.closeEntry()
        }
    }.toByteArray()

    private fun server(): TinyHttpServer {
        lateinit var s: TinyHttpServer
        s = TinyHttpServer { req ->
            when {
                req.path == "/books/" -> when (req.queryParams()["page"]) {
                    null -> TinyHttpServer.Response(200, fixture("page1.json", s.baseUrl))
                    "2" -> TinyHttpServer.Response(200, fixture("page2.json", s.baseUrl))
                    else -> TinyHttpServer.Response(404, "{\"detail\": \"Invalid page.\"}")
                }
                req.path.endsWith(".epub3.images") || req.path.endsWith(".epub.noimages") ->
                    TinyHttpServer.Response(200, fakeEpub, "application/epub+zip")
                req.path == "/html-instead.epub" -> TinyHttpServer.Response(200, "<html><body>Bitte anmelden</body></html>", "text/html")
                else -> TinyHttpServer.Response(404, "not found", "text/plain")
            }
        }
        return s
    }

    @Test
    fun searchParsesResultsAndQuery() = runBlocking {
        server().use { s ->
            val g = Gutenberg(OkHttpClient(), s.baseUrl)
            val page = g.search("  goethe ", listOf("de", "en"))
            val q = s.requests.last().queryParams()
            assertEquals("/books/", s.requests.last().path)
            assertEquals("goethe", q["search"])
            assertEquals("de,en", q["languages"])
            assertEquals("application/epub", q["mime_type"])
            assertNull(q["page"])

            assertEquals(4, page.count)
            assertTrue(page.hasNext)
            assertEquals(3, page.results.size)
            val faust = page.results[0]
            assertEquals(2229, faust.id)
            assertEquals("Faust: Der Tragödie erster Teil", faust.title)
            assertEquals("Johann Wolfgang von Goethe", faust.authors[0].displayName)
            assertEquals("1749–1832", faust.authors[0].lifeSpan)
            assertEquals(listOf("de"), faust.languages)
            assertEquals(4821, faust.downloadCount)
            assertEquals(s.baseUrl + "/ebooks/2229.epub3.images", faust.epubUrl)
            assertEquals(s.baseUrl + "/cache/epub/2229/pg2229.cover.medium.jpg", faust.coverUrl)

            val werther = page.results[1]
            assertEquals("Die Leiden des jungen Werther — Band 1", werther.title)
            assertEquals(s.baseUrl + "/ebooks/2407.epub.noimages", werther.epubUrl)
            assertNull(werther.coverUrl)

            val leonardo = page.results[2]
            assertNull(leonardo.epubUrl)
            assertEquals(0, leonardo.downloadCount)
            assertEquals("Anonymous", leonardo.authorNames)
            assertNull(leonardo.authors[0].lifeSpan)

            val second = g.search("goethe", listOf("de", "en"), page = 2)
            assertEquals("2", s.requests.last().queryParams()["page"])
            assertFalse(second.hasNext)
            assertEquals(listOf(2230), second.results.map { it.id })

            val beyond = g.search("goethe", listOf("de", "en"), page = 3)
            assertTrue(beyond.results.isEmpty())
            assertFalse(beyond.hasNext)
        }
    }

    @Test
    fun emptyQueryAndAllLanguagesOmitParameters() = runBlocking {
        server().use { s ->
            Gutenberg(OkHttpClient(), s.baseUrl + "/").search("", emptyList())
            val q = s.requests.last().queryParams()
            assertNull(q["search"])
            assertNull(q["languages"])
            assertEquals("/books/", s.requests.last().path)
        }
    }

    @Test
    fun epubChoiceIsConsistent() {
        val formats = mapOf(
            "application/epub+zip; variant=noimages" to "https://www.gutenberg.org/ebooks/84.epub.noimages",
            "application/epub+zip; variant=images" to "https://www.gutenberg.org/ebooks/84.epub.images",
            "application/epub+zip" to "https://www.gutenberg.org/ebooks/84.epub3.images",
            "image/jpeg" to "https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg",
        )
        assertEquals("https://www.gutenberg.org/ebooks/84.epub3.images", Gutenberg.pickEpub(formats))
        assertEquals("https://www.gutenberg.org/ebooks/84.epub.images", Gutenberg.pickEpub(formats - "application/epub+zip"))
        assertNull(Gutenberg.pickEpub(mapOf("text/html" to "https://www.gutenberg.org/ebooks/84.html.images")))
    }

    @Test
    fun downloadWritesEpubWithProgress() = runBlocking {
        server().use { s ->
            val g = Gutenberg(OkHttpClient(), s.baseUrl)
            val book = g.search("goethe", listOf("de")).results[0]
            val dest = File(tmp.root, "gutenberg/pg2229.epub")
            val progress = mutableListOf<Float>()
            g.downloadEpub(book, dest) { progress += it }
            assertArrayEquals(fakeEpub, dest.readBytes())
            assertEquals(1f, progress.last())
            assertEquals(progress.sorted(), progress)
            assertTrue(progress.size > 2)
            assertFalse(File(dest.path + ".part").exists())
        }
    }

    @Test
    fun downloadRejectsNonEpubAndHttpErrors() = runBlocking {
        server().use { s ->
            val g = Gutenberg(OkHttpClient(), s.baseUrl)
            val dest = File(tmp.root, "x.epub")
            val html = GutenbergBook(1, "X", emptyList(), listOf("de"), 0, s.baseUrl + "/html-instead.epub", null)
            try {
                g.downloadEpub(html, dest) {}
                fail("expected GutenbergException")
            } catch (e: GutenbergException) {
                assertEquals(GutenbergException.Reason.NOT_EPUB, e.reason)
            }
            assertFalse(dest.exists())
            assertFalse(File(dest.path + ".part").exists())

            val missing = html.copy(epubUrl = s.baseUrl + "/ebooks/404.epub3")
            try {
                g.downloadEpub(missing, dest) {}
                fail("expected GutenbergException")
            } catch (e: GutenbergException) {
                assertEquals(GutenbergException.Reason.NO_EPUB, e.reason)
                assertEquals(404, e.httpCode)
            }

            try {
                g.downloadEpub(html.copy(epubUrl = null), dest) {}
                fail("expected GutenbergException")
            } catch (e: GutenbergException) {
                assertEquals(GutenbergException.Reason.NO_EPUB, e.reason)
            }
        }
    }

    @Test
    fun badJsonAndServerErrors() = runBlocking {
        TinyHttpServer { req ->
            if (req.queryParams()["search"] == "kaputt") TinyHttpServer.Response(200, "<html>Wartung</html>", "text/html")
            else TinyHttpServer.Response(503, "{\"detail\": \"down\"}")
        }.use { s ->
            val g = Gutenberg(OkHttpClient(), s.baseUrl, catalogUrl = null)
            try {
                g.search("kaputt", emptyList()); fail("expected GutenbergException")
            } catch (e: GutenbergException) {
                assertEquals(GutenbergException.Reason.BAD_RESPONSE, e.reason)
            }
            try {
                g.search("x", emptyList()); fail("expected GutenbergException")
            } catch (e: GutenbergException) {
                assertEquals(GutenbergException.Reason.HTTP, e.reason)
                assertEquals(503, e.httpCode)
            }
        }
    }

    private fun brokenGutendex(code: Int): TinyHttpServer {
        lateinit var s: TinyHttpServer
        s = TinyHttpServer { req ->
            when (req.path) {
                "/books/" -> TinyHttpServer.Response(code, "Bad Gateway", "text/plain")
                "/ebooks/search.opds/" -> TinyHttpServer.Response(200, fixture("catalog.xml", s.baseUrl), "application/atom+xml")
                else -> TinyHttpServer.Response(404, "not found", "text/plain")
            }
        }
        return s
    }

    @Test
    fun fallsBackToCatalogWhenGutendexFails() = runBlocking {
        brokenGutendex(502).use { s ->
            val g = Gutenberg(OkHttpClient(), s.baseUrl, s.baseUrl)
            val page = g.search(" kafka ", listOf("de"))
            val q = s.requests.last().queryParams()
            assertEquals("/ebooks/search.opds/", s.requests.last().path)
            assertEquals("kafka l.de", q["query"])
            assertNull(q["start_index"])
            assertEquals(-1, page.count)
            assertTrue(page.hasNext)
            // Navigationseintrag "Authors" fällt weg; eine Sprache filtert der Katalog selbst
            assertEquals(listOf(22367, 7849, 77268), page.results.map { it.id })
            val verwandlung = page.results[0]
            assertEquals("Die Verwandlung", verwandlung.title)
            assertEquals(listOf("de"), verwandlung.languages)
            assertEquals("Franz Kafka", verwandlung.authorNames)
            assertEquals(0, verwandlung.downloadCount)
            assertEquals(s.baseUrl + "/ebooks/22367.epub3.images", verwandlung.epubUrl)
            assertEquals(s.baseUrl + "/cache/epub/22367/pg22367.cover.medium.jpg", verwandlung.coverUrl)
            assertEquals(listOf("en"), page.results[1].languages)
            assertEquals("Képzelet-királyfiak : Mese", page.results[2].title)
            assertEquals(listOf("hu"), page.results[2].languages)

            // Die zweite Seite fragt Gutendex nicht noch einmal
            val gutendexCalls = s.requests.count { it.path == "/books/" }
            g.search("kafka", listOf("de"), page = 2)
            assertEquals("26", s.requests.last().queryParams()["start_index"])
            assertEquals(gutendexCalls, s.requests.count { it.path == "/books/" })
        }
    }

    @Test
    fun catalogFiltersSeveralLanguagesItself() = runBlocking {
        brokenGutendex(503).use { s ->
            val page = Gutenberg(OkHttpClient(), s.baseUrl, s.baseUrl).search("", listOf("de", "en"))
            val q = s.requests.last().queryParams()
            assertEquals("", q["query"])
            assertEquals("downloads", q["sort_order"])
            assertEquals(listOf(22367, 7849), page.results.map { it.id })
        }
    }

    @Test
    fun clientErrorsDoNotFallBack() = runBlocking {
        brokenGutendex(400).use { s ->
            try {
                Gutenberg(OkHttpClient(), s.baseUrl, s.baseUrl).search("x", emptyList()); fail("expected GutenbergException")
            } catch (e: GutenbergException) {
                assertEquals(400, e.httpCode)
            }
            assertTrue(s.requests.none { it.path == "/ebooks/search.opds/" })
        }
    }

    @Test
    fun connectionErrorIsReported() = runBlocking {
        val closed = TinyHttpServer { TinyHttpServer.Response(200, "{}") }
        val url = closed.baseUrl
        closed.close()
        try {
            Gutenberg(OkHttpClient(), url, catalogUrl = null).search("x", emptyList()); fail("expected GutenbergException")
        } catch (e: GutenbergException) {
            assertEquals(GutenbergException.Reason.NETWORK, e.reason)
        }
    }
}
