package app.simplenarrator.input

import app.simplenarrator.testutil.TinyHttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Herauslösen von Webartikeln aus realistischen, selbst gebauten Seiten (ohne Netz). */
class WebArticleTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/web/$name")) { "missing fixture $name" }.readText()

    private fun texts(a: Article) = a.blocks.map { it.text }

    @Test
    fun newsArticleWithoutBoilerplate() {
        val a = WebArticle.extract(fixture("news.html"), "https://www.lokalzeitung.example/lokales/radwegenetz-123")
        assertEquals("Stadtrat beschließt neues Radwegenetz", a.title)
        assertEquals("Anna Muster", a.byline)
        assertEquals("Lokalzeitung Beispielstadt", a.siteName)
        assertEquals("de", a.language)
        assertEquals("https://www.lokalzeitung.example/bilder/radweg.jpg", a.imageUrl)
        assertEquals(
            listOf(
                "Nach monatelanger Debatte hat der Stadtrat am Dienstagabend ein neues Radwegenetz für die Innenstadt beschlossen. Bis 2030 sollen 42 Kilometer geschützte Radwege entstehen, finanziert aus Landes- und Bundesmitteln.",
                "Die Entscheidung fiel mit 31 zu 17 Stimmen. Vor allem die Opposition hatte kritisiert, dass dafür an mehreren Hauptstraßen Parkplätze wegfallen, insgesamt rund 600 Stellplätze.",
                "Was sich für Pendler ändert",
                "Auf der Hauptstraße, der Bahnhofstraße und am Ring werden die Radwege baulich von der Fahrbahn getrennt. Ampeln sollen künftig länger Grün für Radfahrer zeigen, an zwölf Kreuzungen sind eigene Abbiegespuren geplant.",
                "Wir holen nach, was andere Städte vor zehn Jahren begonnen haben, sagte Oberbürgermeisterin Petra Beispiel nach der Sitzung.",
                "Der Zeitplan sieht drei Bauabschnitte vor:",
                "2027: Ring und Bahnhofstraße",
                "2028: Hauptstraße zwischen Markt und Brücke",
                "2029 und 2030: Verbindungen in die Stadtteile",
                "Die Verwaltung rechnet mit Kosten von 38 Millionen Euro, davon trägt die Stadt etwa ein Viertel. Der Einzelhandelsverband kündigte an, die Planung kritisch zu begleiten, begrüßte aber die neuen Abstellanlagen am Markt.",
            ),
            texts(a),
        )
        assertEquals(2, a.blocks[2].headingLevel)
        val all = texts(a).joinToString("\n")
        for (noise in listOf("Cookies", "Politik", "Anzeige", "Lesen Sie auch", "Teilen", "Mehr zum Thema", "Kommentare", "Endlich", "Newsletter", "Meistgelesen", "Impressum", "Verkehr", "Anna Muster")) {
            assertFalse("found boilerplate: $noise", all.contains(noise))
        }
    }

    @Test
    fun blogPostWithoutSidebarAndComments() {
        val a = WebArticle.extract(fixture("blog.html"), "https://janes-kitchen.example/2026/03/sourdough/")
        assertEquals("How I Learned to Bake Sourdough Bread", a.title)
        assertEquals("Jane Doe", a.byline)
        assertEquals("janes-kitchen.example", a.siteName)
        assertEquals("en", a.language)
        assertNull(a.imageUrl)
        assertEquals(
            listOf(
                "For years I was convinced that sourdough was something only professional bakers could manage. The starter seemed fussy, the schedules confusing, and every recipe I found assumed I already knew what I was doing.",
                "Last winter, with more time at home than usual, I finally decided to try it properly. This post is a summary of what worked for me, what did not, and what I would tell a friend who wants to start.",
                "Starting the starter",
                "A starter is nothing more than flour and water that has been colonised by wild yeast and bacteria. I mixed fifty grams of whole wheat flour with fifty grams of lukewarm water, covered the jar loosely, and left it on the kitchen counter.",
                "Feed it every day at the same time.",
                "Discard half before each feeding, otherwise the jar overflows within a week.",
                "Signs that it is ready",
                "It doubles in size within six hours of feeding.",
                "It smells pleasantly sour, a little like yoghurt.",
                "A spoonful floats in water.",
                "The first loaf",
                "My first loaf was flat, dense and slightly grey, but it tasted better than any bread I had bought in months. The second one, baked with a longer cold proof in the fridge, finally had the open crumb I had been hoping for.",
                "If you try this yourself, be patient with the first few attempts. Write down your timings, temperatures and flour, because small changes make a big difference.",
            ),
            texts(a),
        )
        assertEquals(listOf(2, 3, 2), a.blocks.filter { it.isHeading }.map { it.headingLevel })
        val all = texts(a).joinToString("\n")
        for (noise in listOf("Skip to content", "Recipes", "Posted on", "Related", "focaccia", "Share this", "Tagged", "thoughts on", "Leave a Reply", "Recent Posts", "home cook", "WordPress")) {
            assertFalse("found boilerplate: $noise", all.contains(noise))
        }
    }

    @Test
    fun pageWithArticleTag() {
        val a = WebArticle.extract(fixture("article.html"), "https://techweekly.example/guides/battery-aging")
        assertEquals("Why Batteries Age, and What You Can Do About It", a.title)
        assertEquals("Sam Lee", a.byline)
        assertEquals("Tech Weekly", a.siteName)
        assertEquals("https://cdn.techweekly.example/img/cells-1200.jpg", a.imageUrl)
        assertEquals(
            listOf(
                "Every rechargeable battery loses capacity over time. A phone that lasted two days when it was new might struggle to reach the evening after three years, even if nothing else about it has changed.",
                "The main reason is chemistry. Inside a lithium-ion cell, small side reactions slowly consume lithium and build up a layer on the electrodes, which makes it harder for the battery to store and release energy.",
                "Heat is the biggest enemy",
                "High temperatures speed up those side reactions considerably. Leaving a phone on a sunny dashboard or charging it under a pillow does more damage than most people expect.",
                "Charging habits that help",
                "Keeping the charge between roughly twenty and eighty percent reduces stress on the cell. Many phones now offer an optimised charging mode that waits before filling the last part of the battery.",
                "Small changes in how you charge can add a year to a battery's useful life.",
                "Fast charging is convenient, but it also produces more heat. If you are not in a hurry, a slower charger overnight is the gentler choice.",
            ),
            texts(a),
        )
        val all = texts(a).joinToString("\n")
        for (noise in listOf("cookies", "Reviews", "5 min read", "Photo:", "Advertisement", "weekly guide", "More from", "laptops", "back up", "independent publication")) {
            assertFalse("found boilerplate: $noise", all.contains(noise))
        }
    }

    @Test
    fun shortArticleBecomesOneChapterWithTitle() {
        val book = WebArticle.toBook(WebArticle.extract(fixture("article.html"), "https://techweekly.example/guides/battery-aging"))
        assertEquals("Why Batteries Age, and What You Can Do About It", book.title)
        assertEquals("Sam Lee", book.author)
        assertEquals(1, book.chapters.size)
        val ch = book.chapters[0]
        assertEquals(book.title, ch.title)
        assertTrue(ch.paragraphs[0].isHeading)
        assertEquals("Why Batteries Age, and What You Can Do About It", ch.paragraphs[0].sentences.joinToString(" ") { it.text })
        assertTrue(ch.paragraphs.flatMap { it.sentences }.all { it.lang == "en" })
    }

    @Test
    fun longArticleIsSplitAtH2() {
        val sb = StringBuilder("<html lang=\"de\"><head><title>Langer Bericht | Beispielmagazin</title></head><body><article><h1>Langer Bericht</h1>")
        sb.append("<p>Dies ist die Einleitung des Berichts, die vor der ersten Zwischenüberschrift steht und den Leser in das Thema einführt.</p>")
        for (part in 1..3) {
            sb.append("<h2>Teil $part</h2>")
            repeat(15) { i -> sb.append("<p>Absatz ${i + 1} in Teil $part erzählt, wie es weiterging, und enthält genug Text, damit er als Absatz zählt.</p>") }
        }
        sb.append("</article></body></html>")
        val book = WebArticle.toBook(WebArticle.extract(sb.toString(), "https://magazin.example/bericht"))
        assertEquals("Langer Bericht", book.title)
        assertEquals("magazin.example", book.author)
        assertEquals(listOf("Langer Bericht", "Teil 1", "Teil 2", "Teil 3"), book.chapters.map { it.title })
        assertEquals(16, book.chapters[1].paragraphs.size) // Überschrift + 15 Absätze
        assertTrue(book.chapters.flatMap { it.paragraphs }.flatMap { it.sentences }.all { it.lang == "de" })
    }

    @Test
    fun shortArticleWithH2StaysOneChapter() {
        val html = "<html><body><article><h1>Kurz</h1><p>Erster Absatz mit etwas Text, damit er gewertet wird und im Ergebnis landet.</p>" +
            "<h2>Abschnitt</h2><p>Zweiter Absatz mit etwas Text, damit er gewertet wird und im Ergebnis landet.</p></article></body></html>"
        val book = WebArticle.toBook(WebArticle.extract(html, "https://example.org/kurz"))
        assertEquals(listOf("Kurz"), book.chapters.map { it.title })
    }

    @Test
    fun itempropArticleBodyIsPreferred() {
        val html = "<html><body><div class=\"wrapper\"><div itemprop=\"articleBody\">" +
            "<p>Der eigentliche Artikeltext steht hier, mit einigen Kommas, damit er sicher gewertet wird.</p>" +
            "<p>Ein zweiter Absatz des Artikels folgt, ebenfalls mit genug Text, um als Inhalt zu gelten.</p>" +
            "<p>Und ein dritter Absatz, damit der Container mehr als zweihundert Zeichen enthält, wie verlangt.</p>" +
            "</div><div class=\"teaser-box\"><p>Ein ganz anderer Text, lang genug, mit Kommas, Kommas, Kommas, Kommas, Kommas und noch mehr Kommas.</p></div></div></body></html>"
        val a = WebArticle.extract(html, "https://example.org/x")
        assertEquals(3, a.blocks.size)
        assertTrue(a.blocks.none { it.text.startsWith("Ein ganz anderer") })
    }

    @Test
    fun titleSuffixes() {
        assertEquals("Stadtrat beschließt neues Radwegenetz", WebArticle.cleanTitle("Stadtrat beschließt neues Radwegenetz | Lokalzeitung Beispielstadt", "Lokalzeitung Beispielstadt"))
        assertEquals("How I Learned to Bake Sourdough Bread", WebArticle.cleanTitle("How I Learned to Bake Sourdough Bread – Jane's Kitchen Notes", ""))
        assertEquals("Why Batteries Age", WebArticle.cleanTitle("Why Batteries Age | Guides | Tech Weekly", "Tech Weekly"))
        assertEquals("Neue Studie zur Hitze", WebArticle.cleanTitle("SPIEGEL - Neue Studie zur Hitze", "SPIEGEL"))
        // Kurzer Titel mit Bindestrich bleibt ganz
        assertEquals("Star Wars - The Last Jedi review", WebArticle.cleanTitle("Star Wars - The Last Jedi review", ""))
        assertEquals("Covid-19 in Zahlen", WebArticle.cleanTitle("Covid-19 in Zahlen", ""))
    }

    @Test
    fun normalizeUrl() {
        assertEquals("https://example.com/a?b=1", WebArticle.normalizeUrl(" example.com/a?b=1 "))
        assertEquals("http://example.com/", WebArticle.normalizeUrl("http://example.com"))
        assertNull(WebArticle.normalizeUrl("ftp://example.com/file"))
        assertNull(WebArticle.normalizeUrl("kein link"))
        assertNull(WebArticle.normalizeUrl("hallo"))
        assertNull(WebArticle.normalizeUrl(""))
    }

    @Test
    fun fetchFollowsRedirectAndSendsBrowserUserAgent() = runBlocking {
        TinyHttpServer { req ->
            when (req.path) {
                "/old" -> TinyHttpServer.Response(301, "", null, mapOf("Location" to "/artikel/neu"))
                "/artikel/neu" -> TinyHttpServer.Response(200, fixture("news.html"), "text/html; charset=utf-8")
                else -> TinyHttpServer.Response(404, "nope", "text/plain")
            }
        }.use { server ->
            val book = WebArticle.fetch(server.baseUrl + "/old", OkHttpClient())
            assertEquals("Stadtrat beschließt neues Radwegenetz", book.title)
            assertEquals("Anna Muster", book.author)
            assertEquals(1, book.chapters.size)
            assertTrue(book.chapters[0].paragraphs.flatMap { it.sentences }.all { it.lang == "de" })
            assertTrue(server.requests.all { it.header("user-agent")?.startsWith("Mozilla/5.0") == true })
            assertEquals(listOf("/old", "/artikel/neu"), server.requests.map { it.path })
        }
    }

    @Test
    fun fetchRejectsNonHtml() = runBlocking {
        TinyHttpServer { TinyHttpServer.Response(200, "%PDF-1.7".toByteArray(), "application/pdf") }.use { server ->
            try {
                WebArticle.fetch(server.baseUrl + "/datei.pdf", OkHttpClient())
                fail("expected WebArticleException")
            } catch (e: WebArticleException) {
                assertEquals(WebArticleException.Reason.NOT_HTML, e.reason)
                assertTrue(e.message!!.contains("application/pdf"))
            }
        }
    }

    @Test
    fun fetchReportsHttpErrorAndEmptyPage() = runBlocking {
        TinyHttpServer { req ->
            if (req.path == "/leer") TinyHttpServer.Response(200, "<html><body><nav><a href='/'>Start</a></nav></body></html>", "text/html")
            else TinyHttpServer.Response(404, "<html><body>Not found</body></html>", "text/html")
        }.use { server ->
            try {
                WebArticle.fetch(server.baseUrl + "/fehlt", OkHttpClient())
                fail("expected WebArticleException")
            } catch (e: WebArticleException) {
                assertEquals(WebArticleException.Reason.HTTP, e.reason)
                assertEquals(404, e.httpCode)
            }
            try {
                WebArticle.fetch(server.baseUrl + "/leer", OkHttpClient())
                fail("expected WebArticleException")
            } catch (e: WebArticleException) {
                assertEquals(WebArticleException.Reason.NO_TEXT, e.reason)
            }
        }
    }
}
