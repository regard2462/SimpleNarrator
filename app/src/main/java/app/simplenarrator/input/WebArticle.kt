package app.simplenarrator.input

import app.simplenarrator.data.ParsedBook
import app.simplenarrator.data.RawParagraph
import app.simplenarrator.parser.LanguageDetector
import app.simplenarrator.parser.TextPipeline
import app.simplenarrator.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/** Ein Textblock des Artikels; headingLevel 2–4 für Zwischenüberschriften, sonst 0. */
data class ArticleBlock(val text: String, val headingLevel: Int = 0) {
    val isHeading: Boolean get() = headingLevel > 0
}

data class Article(
    val url: String,
    val title: String,
    val byline: String,
    val siteName: String,
    /** Sprache aus <html lang>, nur "de" oder "en", sonst null. */
    val language: String?,
    /** og:image, als Cover verwendbar (siehe WebArticle.fetchImage). */
    val imageUrl: String?,
    /** Artikeltext ohne Titel, in Lesereihenfolge. */
    val blocks: List<ArticleBlock>,
)

class WebArticleException(
    val reason: Reason,
    message: String,
    val httpCode: Int = 0,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason { INVALID_URL, NETWORK, HTTP, NOT_HTML, TOO_LARGE, NO_TEXT }
}

/**
 * Webartikel als Buch: Seite laden, Navigation, Werbung, Kommentare und ähnliche Blöcke entfernen,
 * den Hauptinhalt nach Textdichte bestimmen (angelehnt an Readability) und daraus Absätze machen.
 */
object WebArticle {
    /** Wie ein Desktop-Browser, manche Seiten liefern sonst nur eine Rumpfseite. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val MAX_PAGE_BYTES = 8L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    /** Ab so vielen Absätzen wird an <h2> in Kapitel geteilt. */
    private const val SPLIT_THRESHOLD = 40

    private val REMOVE_TAGS = setOf(
        "script", "style", "nav", "header", "footer", "aside", "form", "noscript", "iframe", "svg", "canvas",
        "button", "input", "select", "textarea", "template", "object", "embed", "video", "audio", "picture",
        "img", "figcaption", "dialog", "link", "meta", "menu",
    )
    private val REMOVE_ROLES = setOf("navigation", "banner", "contentinfo", "complementary", "dialog", "alertdialog", "search", "menu", "menubar")

    /** Klassen- oder ID-Bestandteile typischer Randblöcke; geprüft wird der Anfang jedes Wortteils. */
    private val BOILERPLATE = Regex(
        "(?i)(^|[-_])(comment(?!ary)|disqus|share|sharing|shariff|social|related|recommend|promo|advert|sponsor|" +
            "cookie|consent|gdpr|newsletter|subscri|signup|paywall|sidebar|footer|menu|breadcrumb|banner|popup|modal|" +
            "overlay|outbrain|taboola|readmore|read-more|byline|dateline|author|meta|tags|taglist|sr-only|visually-hidden|" +
            "screen-reader|skip|print-only|toolbar|widget|pagination|pager)"
    )
    /** Dasselbe in camelCase-Namen ("articleComments", "shareBar" wird oben schon erkannt). */
    private val CAMEL_BOILERPLATE = Regex(
        "(?<=[a-z])(Comments?|Disqus|Share|Sharing|Social|Related|Recommend|Promo|Advert|Sponsor|Cookie|Newsletter|" +
            "Subscri|Paywall|Sidebar|Footer|Menu|Breadcrumb|Banner|Popup|Modal|Outbrain|Taboola|Byline|Author)"
    )
    /** Werbeplätze: "ad", "ads", "ad-slot", "top-ad", "anzeige" … */
    private val AD_TOKEN = Regex("(?i)^(ad|ads|adv|advert\\w*|anzeige\\w*|werbung\\w*)([-_].*)?$|^.+[-_](ad|ads)$")
    /** Container, die nie als Randblock gelten, auch wenn ein Wortteil oben passt. */
    private val CONTENT_HINT = Regex("(?i)(^|[-_])(article|post|entry|story|main|page)[-_]?(body|content|text)|^(content|main-content|articlebody|entry-content|post-content)$")

    private val POSITIVE = Regex("(?i)article|body|content|entry|hentry|main|page|post|text|blog|story")
    private val NEGATIVE = Regex("(?i)hidden|banner|combx|comment|contact|foot|masthead|media|meta|promo|related|scroll|share|shoutbox|sidebar|sponsor|shopping|tags|tool|widget|teaser")

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "blockquote", "li", "ul", "ol", "dl", "dt", "dd",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "table", "thead", "tbody", "tr", "figure", "hr",
        "address", "center", "details", "summary", "caption",
    )

    /** Kurze Absätze, die nur Randhinweise sind ("Anzeige", "Lesen Sie auch", Bildnachweise). */
    private val BOILERPLATE_TEXT = Regex(
        "(?i)^(advertisement|anzeige|werbung|sponsored|read more|weiterlesen|mehr zum thema|lesen sie auch|auch interessant|" +
            "das könnte sie auch interessieren|related( articles| stories)?|see also|share( this( article| story)?)?|teilen|" +
            "subscribe|abonnieren|newsletter|zum newsletter|image|bild|foto|photo|fotos|credit|quelle)\\b.*"
    )
    private val NOTE_MARK = Regex("^\\[?\\d{1,3}\\]?$")
    private val TITLE_SEPARATOR = Regex("\\s+(\\||–|—|-|·|•|::|»|/)\\s+")
    private val WHITESPACE = Regex("\\s+")

    // ---------------------------------------------------------------- Laden

    /** Seite laden und gleich in ein Buch umwandeln. */
    suspend fun fetch(url: String, http: OkHttpClient): ParsedBook = toBook(fetchArticle(url, http))

    /** Seite laden und den Artikel herauslösen (mit Metadaten, z. B. für das Cover aus og:image). */
    suspend fun fetchArticle(url: String, http: OkHttpClient): Article = withContext(Dispatchers.IO) {
        val target = normalizeUrl(url)?.toHttpUrlOrNull()
            ?: throw WebArticleException(WebArticleException.Reason.INVALID_URL, "Invalid web address: $url")
        val client = http.newBuilder().followRedirects(true).followSslRedirects(true).build()
        val request = Request.Builder().url(target)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw WebArticleException(WebArticleException.Reason.NETWORK, e.message ?: e.javaClass.simpleName, cause = e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw WebArticleException(WebArticleException.Reason.HTTP, "HTTP ${resp.code} for $target", resp.code)
            val body = resp.body ?: throw WebArticleException(WebArticleException.Reason.NO_TEXT, "Empty response from $target")
            val type = body.contentType()
            if (type != null && !isHtml(type)) {
                throw WebArticleException(WebArticleException.Reason.NOT_HTML, "Not an HTML page: ${type.type}/${type.subtype}")
            }
            val bytes = readLimited(body, MAX_PAGE_BYTES)
                ?: throw WebArticleException(WebArticleException.Reason.TOO_LARGE, "Page larger than ${MAX_PAGE_BYTES / 1024 / 1024} MB")
            if (type == null && !looksLikeHtml(bytes)) {
                throw WebArticleException(WebArticleException.Reason.NOT_HTML, "Not an HTML page (no content type)")
            }
            val finalUrl = resp.request.url.toString()
            // Zeichensatz aus dem Header, sonst erkennt jsoup ihn aus BOM bzw. <meta charset>
            val doc = Jsoup.parse(ByteArrayInputStream(bytes), type?.charset()?.name(), finalUrl)
            extract(doc, finalUrl)
        }
    }

    /** Bild (z. B. og:image) für das Cover laden; bei jedem Fehler null. */
    suspend fun fetchImage(url: String, http: OkHttpClient): ByteArray? = withContext(Dispatchers.IO) {
        val target = url.toHttpUrlOrNull() ?: return@withContext null
        try {
            http.newCall(Request.Builder().url(target).header("User-Agent", USER_AGENT).build()).await().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null || body.contentType()?.type != "image") null
                else readLimited(body, MAX_IMAGE_BYTES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Eingabe zu einer http(s)-Adresse ergänzen ("example.com/x" → "https://example.com/x"); ungültig → null. */
    fun normalizeUrl(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val withScheme = when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.contains("://") -> return null
            else -> "https://$s"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (!url.host.contains('.') && url.host != "localhost") return null
        return url.toString()
    }

    private fun isHtml(type: MediaType): Boolean =
        type.subtype.equals("html", true) || type.subtype.equals("xhtml+xml", true)

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, min(bytes.size, 1024), Charsets.ISO_8859_1).lowercase()
        return "<html" in head || "<!doctype html" in head || "<head" in head || "<body" in head
    }

    /** Liest höchstens max Bytes; größer → null. */
    private fun readLimited(body: ResponseBody, max: Long): ByteArray? {
        if (body.contentLength() > max) return null
        val out = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(1 shl 14)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- Herauslösen

    fun extract(html: String, url: String): Article = extract(Jsoup.parse(html, url), url)

    fun extract(doc: Document, url: String): Article {
        // Metadaten zuerst, bevor Kopfbereich und Randblöcke entfernt werden
        val siteName = meta(doc, "og:site_name") ?: hostOf(url)
        val title = findTitle(doc, siteName)
        val byline = findByline(doc)
        val language = doc.selectFirst("html")?.attr("lang")?.lowercase()?.take(2)?.takeIf { it == "de" || it == "en" }
        val imageUrl = doc.selectFirst("meta[property=\"og:image\"], meta[name=\"og:image\"], meta[name=\"twitter:image\"]")
            ?.absUrl("content")?.takeIf { it.startsWith("http") }

        val body = doc.body()
        removeBoilerplate(body)
        // Vorne weg: Wiederholung des Titels und kurze Rubrikzeilen ("Verkehr", "Exklusiv")
        val blocks = pickContent(body).flatMap { extractBlocks(it) }
            .dropWhile { b ->
                b.text.equals(title, ignoreCase = true) ||
                    (!b.isHeading && words(b.text) < 4 && b.text.last() !in ".!?:")
            }
        return Article(url, title, byline, siteName, language, imageUrl, blocks)
    }

    /** Artikel → Buch. Lange Artikel (> 40 Absätze) werden an <h2> in Kapitel geteilt. */
    fun toBook(article: Article): ParsedBook {
        val blocks = article.blocks
        if (blocks.none { !it.isHeading }) throw WebArticleException(WebArticleException.Reason.NO_TEXT, "No article text found at ${article.url}")
        val title = article.title.ifBlank { article.siteName }
        val sample = blocks.joinToString("\n") { it.text }
        val lang = LanguageDetector.detect(sample.take(6000)) ?: article.language ?: TextPipeline.guessBookLanguage(sample)

        val groups = mutableListOf<Pair<String, MutableList<RawParagraph>>>()
        var current = title to mutableListOf<RawParagraph>().apply { if (title.isNotBlank()) add(RawParagraph(title, true)) }
        val split = blocks.count { !it.isHeading } > SPLIT_THRESHOLD && blocks.any { it.headingLevel == 2 }
        for (b in blocks) {
            if (split && b.headingLevel == 2) {
                groups += current
                current = b.text to mutableListOf(RawParagraph(b.text, true))
            } else current.second += RawParagraph(b.text, b.isHeading)
        }
        groups += current
        // Einleitung ohne eigenen Text: den Titel ins erste Kapitel übernehmen
        if (groups.size > 1 && groups[0].second.none { !it.isHeading }) {
            val intro = groups.removeAt(0)
            groups[0].second.addAll(0, intro.second)
        }
        val chapters = groups.map { (t, raw) -> TextPipeline.buildChapter(t, raw, lang) }.filter { it.sentenceCount > 0 }
        return ParsedBook(title, article.byline.ifBlank { article.siteName }, null, chapters)
    }

    // ---------------------------------------------------------------- Metadaten

    private fun meta(doc: Document, name: String): String? =
        doc.selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]")?.attr("content")
            ?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()

    private fun findTitle(doc: Document, siteName: String): String {
        val h1 = (doc.selectFirst("article h1, main h1") ?: doc.selectFirst("h1"))?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val raw = meta(doc, "og:title") ?: meta(doc, "twitter:title") ?: h1 ?: doc.title()
        return cleanTitle(raw, siteName)
    }

    /** " | Seitenname", " - Seitenname" und ähnliche Anhänge entfernen. */
    internal fun cleanTitle(raw: String, siteName: String): String {
        var t = raw.replace(WHITESPACE, " ").trim()
        repeat(2) {
            val parts = TITLE_SEPARATOR.findAll(t).toList()
            if (parts.isEmpty()) return t
            val last = parts.last()
            val head = t.substring(0, last.range.first).trim()
            val tail = t.substring(last.range.last + 1).trim()
            val first = parts.first()
            val firstHead = t.substring(0, first.range.first).trim()
            t = when {
                siteName.isNotBlank() && tail.equals(siteName, true) -> head
                siteName.isNotBlank() && firstHead.equals(siteName, true) -> t.substring(first.range.last + 1).trim()
                words(tail) <= 4 && words(head) >= 3 -> head
                else -> return t
            }
        }
        return t
    }

    private fun words(s: String) = s.split(' ').count { it.isNotBlank() }

    private fun findByline(doc: Document): String {
        val candidates = sequence {
            yield(meta(doc, "author"))
            yield(meta(doc, "article:author")?.takeUnless { it.startsWith("http") })
            yield(doc.selectFirst("[rel=author]")?.text())
            yield(doc.selectFirst("[itemprop=author] [itemprop=name]")?.text())
            yield(doc.selectFirst("[itemprop=author]")?.text())
            yield(doc.selectFirst(".byline, .author")?.text())
        }
        return candidates.mapNotNull { cleanByline(it) }.firstOrNull().orEmpty()
    }

    private fun cleanByline(raw: String?): String? {
        if (raw == null) return null
        val s = raw.replace(WHITESPACE, " ").trim()
            .substringBefore(" | ").substringBefore(" · ").substringBefore(" • ")
            .replace(Regex("(?i)^(by|von|text:|autor:|author:)\\s*"), "")
            .trim()
        return s.takeIf { it.isNotEmpty() && it.length <= 100 }
    }

    // ---------------------------------------------------------------- Aufräumen

    private fun removeBoilerplate(body: Element) {
        for (e in body.select("*")) {
            if (e === body || e.parent() == null) continue
            if (isBoilerplate(e)) e.remove()
        }
    }

    private fun isBoilerplate(e: Element): Boolean {
        val tag = e.normalName()
        if (tag in REMOVE_TAGS) return true
        if (e.attr("role").lowercase() in REMOVE_ROLES) return true
        if (e.hasAttr("hidden") || e.attr("aria-hidden") == "true") return true
        val style = e.attr("style").lowercase().replace(" ", "")
        if ("display:none" in style || "visibility:hidden" in style) return true
        if (tag == "article" || tag == "main" || e.attr("itemprop") == "articleBody") return false
        val tokens = e.classNames().toMutableList().apply { if (e.id().isNotEmpty()) add(e.id()) }
        if (tokens.isEmpty()) return false
        if (tokens.any { CONTENT_HINT.containsMatchIn(it) }) return false
        return tokens.any { BOILERPLATE.containsMatchIn(it) || CAMEL_BOILERPLATE.containsMatchIn(it) || AD_TOKEN.matches(it) }
    }

    // ---------------------------------------------------------------- Hauptinhalt finden

    private fun textLength(e: Element) = e.text().length

    /** Anteil der Zeichen in Links (Sprungmarken innerhalb der Seite zählen nicht). */
    private fun linkDensity(e: Element): Double {
        val total = textLength(e)
        if (total == 0) return 0.0
        val links = e.select("a[href]").filterNot { it.attr("href").startsWith("#") }.sumOf { it.text().length }
        return links.toDouble() / total
    }

    private fun classWeight(e: Element): Double {
        var w = 0.0
        val cls = e.className()
        val id = e.id()
        if (cls.isNotEmpty()) { if (NEGATIVE.containsMatchIn(cls)) w -= 25; if (POSITIVE.containsMatchIn(cls)) w += 25 }
        if (id.isNotEmpty()) { if (NEGATIVE.containsMatchIn(id)) w -= 25; if (POSITIVE.containsMatchIn(id)) w += 25 }
        return w
    }

    private fun initialScore(e: Element): Double = classWeight(e) + when (e.normalName()) {
        "div", "article", "section", "main" -> 5.0
        "pre", "td", "blockquote" -> 3.0
        "address", "ol", "ul", "dl", "dd", "dt", "li", "form" -> -3.0
        "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5.0
        else -> 0.0
    }

    /**
     * Readability-ähnlich: Jeder Absatz gibt Punkte an Eltern (voll), Großeltern (halb) und Urgroßeltern (ein Sechstel);
     * Punkte nach Länge und Kommas, abgewertet nach Linkdichte. Bevorzugt werden [itemprop=articleBody] und das
     * <article>, in dem der beste Kandidat liegt. Sonst kommen passende Geschwister des besten Kandidaten dazu.
     */
    private fun pickContent(body: Element): List<Element> {
        body.select("[itemprop=articleBody]").maxByOrNull { textLength(it) }?.takeIf { textLength(it) >= 200 }?.let { return listOf(it) }

        val scores = IdentityHashMap<Element, Double>()
        val paragraphs = body.select("p, pre, td").toMutableList()
        // <div> ohne Blockkinder wird wie ein Absatz behandelt (Seiten mit <div> statt <p>)
        body.select("div").filterTo(paragraphs) { d -> d.children().none { it.normalName() in BLOCK_TAGS } }
        for (p in paragraphs) {
            val text = p.text()
            if (text.length < 25) continue
            val score = 1.0 + text.count { it == ',' || it == '，' } + min(text.length / 100, 3)
            var anc = p.parent()
            var level = 0
            while (anc != null && level < 3) {
                val base = scores[anc] ?: initialScore(anc)
                val divider = when (level) { 0 -> 1.0; 1 -> 2.0; else -> level * 3.0 }
                scores[anc] = base + score / divider
                if (anc === body) break
                anc = anc.parent()
                level++
            }
        }
        if (scores.isEmpty()) {
            return listOf(body.selectFirst("article") ?: body.selectFirst("main") ?: body)
        }
        val final = scores.mapValues { (e, s) -> s * (1 - linkDensity(e)) }
        val top = final.maxByOrNull { it.value }!!.key

        // Liegt der Kandidat in einem <article>, dann das ganze <article> (Überschriften, aufgeteilte Textblöcke)
        (if (top.normalName() == "article") top else top.closest("article"))?.let { return listOf(it) }
        if (top === body) return listOf(body)

        val parent = top.parent() ?: return listOf(top)
        val topScore = final[top] ?: 0.0
        val threshold = max(10.0, topScore * 0.2)
        val siblings = parent.children()
        val keep = siblings.map { sib ->
            when {
                sib === top -> true
                (final[sib] ?: 0.0) >= threshold -> true
                sib.normalName() == "p" -> {
                    val len = textLength(sib)
                    val density = linkDensity(sib)
                    (len > 80 && density < 0.25) || (len in 1..80 && density == 0.0 && sib.text().trimEnd().endsWith('.'))
                }
                else -> false
            }
        }
        val firstKept = keep.indexOfFirst { it }
        val lastKept = keep.indexOfLast { it }
        // Zwischenüberschriften zwischen den behaltenen Blöcken (und direkt davor) gehören dazu
        return siblings.filterIndexed { i, sib ->
            keep[i] || (i in (firstKept - 1)..lastKept && sib.normalName() in setOf("h2", "h3", "h4"))
        }
    }

    // ---------------------------------------------------------------- Absätze

    private fun headingLevel(tag: String): Int = when (tag) {
        "h2" -> 2; "h3" -> 3; "h4" -> 4
        else -> 0
    }

    /** Läuft durch den DOM und schneidet an Blockgrenzen (und an doppeltem <br>) Absätze ab. */
    private fun extractBlocks(root: Element): List<ArticleBlock> {
        val out = mutableListOf<ArticleBlock>()
        val sb = StringBuilder()
        var linkChars = 0
        var level = 0
        var brRun = 0
        fun flush() {
            val t = sb.toString().replace(WHITESPACE, " ").trim()
            if (t.isNotEmpty()) {
                val linkHeavy = linkChars > 0 && linkChars.toDouble() / t.length > 0.5
                val noise = t.length < 140 && BOILERPLATE_TEXT.matches(t)
                if (!linkHeavy && !noise && !NOTE_MARK.matches(t)) out += ArticleBlock(t, level)
            }
            sb.setLength(0)
            linkChars = 0
            level = 0
            brRun = 0
        }
        fun walk(node: Node, inLink: Boolean) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                    if (text.isNotBlank()) brRun = 0
                    sb.append(text)
                    if (inLink) linkChars += text.replace(WHITESPACE, " ").trim().length
                }
                is Element -> {
                    val tag = node.normalName()
                    if (tag == "h1") return                          // Titel steht schon am Anfang
                    if (tag == "sup" && NOTE_MARK.matches(node.text().trim())) return   // Fußnotenziffern
                    if (tag == "br") {
                        brRun++
                        if (brRun >= 2) flush() else sb.append('\n')
                        return
                    }
                    val block = tag in BLOCK_TAGS
                    if (block) flush()
                    if (tag == "td" || tag == "th") sb.append(' ')
                    val h = headingLevel(tag)
                    if (h > 0) level = h
                    val link = inLink || (tag == "a" && node.hasAttr("href") && !node.attr("href").startsWith("#"))
                    node.childNodes().forEach { walk(it, link) }
                    if (block) flush()
                }
            }
        }
        walk(root, false)
        flush()
        return out
    }
}
