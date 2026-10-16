package app.simplenarrator.input

import app.simplenarrator.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class GutenbergPerson(
    val name: String = "",
    @SerialName("birth_year") val birthYear: Int? = null,
    @SerialName("death_year") val deathYear: Int? = null,
) {
    /** Gutendex liefert "Nachname, Vorname"; für die Anzeige "Vorname Nachname". */
    val displayName: String
        get() {
            val parts = name.split(", ", limit = 2)
            return if (parts.size == 2 && ',' !in parts[1]) "${parts[1]} ${parts[0]}" else name
        }

    /** "1797–1851", "1950–" oder null. */
    val lifeSpan: String?
        get() = when {
            birthYear != null || deathYear != null -> "${birthYear ?: ""}–${deathYear ?: ""}"
            else -> null
        }
}

data class GutenbergBook(
    val id: Int,
    val title: String,
    val authors: List<GutenbergPerson>,
    val languages: List<String>,
    val downloadCount: Int,
    /** EPUB-Adresse, siehe Gutenberg.pickEpub; null, wenn das Buch kein EPUB hat. */
    val epubUrl: String?,
    /** Titelbild (image/jpeg), meist die mittlere Größe. */
    val coverUrl: String?,
) {
    val authorNames: String get() = authors.joinToString(", ") { it.displayName }
}

data class GutenbergPage(
    val results: List<GutenbergBook>,
    /** Treffer insgesamt über alle Seiten. */
    val count: Int,
    val page: Int,
    val hasNext: Boolean,
)

class GutenbergException(
    val reason: Reason,
    message: String,
    val httpCode: Int = 0,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason { NETWORK, HTTP, BAD_RESPONSE, NO_EPUB, NOT_EPUB }
}

/**
 * Suche und Download gemeinfreier Bücher von Project Gutenberg über die Gutendex-API
 * (https://gutendex.com, GET /books/?search=…&languages=de,en&page=N, 32 Treffer pro Seite).
 * Antwortet Gutendex nicht, sucht der Client im OPDS-Katalog von gutenberg.org ([catalogUrl], null schaltet das ab).
 */
class Gutenberg(
    private val http: OkHttpClient,
    baseUrl: String = "https://gutendex.com",
    catalogUrl: String? = "https://www.gutenberg.org",
    gutendexTimeoutSeconds: Long = 15,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val catalog: HttpUrl? = catalogUrl?.trimEnd('/')?.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    // Gutendex hängt zeitweise fast eine Minute und liefert dann nichts; so lange soll niemand warten
    private val gutendexHttp = http.newBuilder().callTimeout(gutendexTimeoutSeconds, TimeUnit.SECONDS).build()
    /** Suchen (Text und Sprachen), die auf den Katalog ausgewichen sind; ihre weiteren Seiten kommen auch von dort. */
    private val catalogSearches = Collections.synchronizedSet(HashSet<String>())

    @Serializable
    private data class BooksDto(
        val count: Int = 0,
        val next: String? = null,
        val previous: String? = null,
        val results: List<BookDto> = emptyList(),
    )

    @Serializable
    private data class BookDto(
        val id: Int,
        val title: String = "",
        val authors: List<GutenbergPerson> = emptyList(),
        val languages: List<String> = emptyList(),
        @SerialName("download_count") val downloadCount: Int = 0,
        val formats: Map<String, String> = emptyMap(),
    )

    /**
     * Suche in Titeln und Autorennamen. Leere Suche liefert die meistgeladenen Bücher.
     * languages: zweistellige Codes ("de", "en"); leer heißt alle Sprachen.
     * Nur Bücher mit EPUB (mime_type-Filter von Gutendex).
     */
    suspend fun search(query: String, languages: List<String>, page: Int = 1): GutenbergPage {
        val langs = languages.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val key = query.trim().replace(Regex("\\s+"), " ").lowercase() + "|" + langs.sorted().joinToString(",")
        if (catalog != null && page > 1 && key in catalogSearches) return searchCatalog(catalog, query, langs, page)
        return try {
            searchGutendex(query, langs, page).also { if (page == 1) catalogSearches.remove(key) }
        } catch (e: GutenbergException) {
            if (catalog == null || page > 1 || !e.worthFallback()) throw e
            try {
                searchCatalog(catalog, query, langs, page).also { catalogSearches += key }
            } catch (c: GutenbergException) {
                c.addSuppressed(e)
                throw c
            }
        }
    }

    private fun GutenbergException.worthFallback() = when (reason) {
        GutenbergException.Reason.NETWORK, GutenbergException.Reason.BAD_RESPONSE -> true
        GutenbergException.Reason.HTTP -> httpCode >= 500 || httpCode == 429
        else -> false
    }

    private suspend fun searchGutendex(query: String, langs: List<String>, page: Int): GutenbergPage = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("books/").apply {
            query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it.replace(Regex("\\s+"), " ")) }
            langs.takeIf { it.isNotEmpty() }?.let { addQueryParameter("languages", it.joinToString(",")) }
            addQueryParameter("mime_type", "application/epub")
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        get(url, gutendexHttp).use { resp ->
            // Gutendex antwortet hinter der letzten Seite mit 404 ("Invalid page.")
            if (resp.code == 404 && page > 1) return@withContext GutenbergPage(emptyList(), 0, page, false)
            if (!resp.isSuccessful) throw GutenbergException(GutenbergException.Reason.HTTP, "HTTP ${resp.code} from $url", resp.code)
            val text = resp.body?.string().orEmpty()
            val dto = try {
                json.decodeFromString<BooksDto>(text)
            } catch (e: SerializationException) {
                throw GutenbergException(GutenbergException.Reason.BAD_RESPONSE, "Unexpected answer from $url", cause = e)
            } catch (e: IllegalArgumentException) {
                throw GutenbergException(GutenbergException.Reason.BAD_RESPONSE, "Unexpected answer from $url", cause = e)
            }
            GutenbergPage(
                results = dto.results.map { b ->
                    GutenbergBook(
                        id = b.id,
                        title = b.title.replace(Regex("\\s+"), " ").trim(),
                        authors = b.authors,
                        languages = b.languages,
                        downloadCount = b.downloadCount,
                        epubUrl = pickEpub(b.formats),
                        coverUrl = b.formats.entries.firstOrNull { mime(it.key) == "image/jpeg" }?.value,
                    )
                },
                count = dto.count,
                page = page,
                hasNext = dto.next != null,
            )
        }
    }

    /**
     * Ausweichsuche im OPDS-Katalog von gutenberg.org (25 Treffer pro Seite). Er nennt weder Downloadzahlen noch
     * die Zahl aller Treffer ([GutenbergPage.count] ist dann -1). Eine Sprache filtert er selbst ("l.de"),
     * bei mehreren sortiert [parseCatalog] nach der Sprachangabe im Titel aus.
     */
    private suspend fun searchCatalog(catalog: HttpUrl, query: String, langs: List<String>, page: Int): GutenbergPage =
        withContext(Dispatchers.IO) {
            val q = query.trim().replace(Regex("\\s+"), " ")
            val terms = listOfNotNull(q.takeIf { it.isNotEmpty() }, langs.singleOrNull()?.let { "l.$it" }).joinToString(" ")
            val url = catalog.newBuilder().addPathSegments("ebooks/search.opds/").apply {
                addQueryParameter("query", terms)
                if (q.isEmpty()) addQueryParameter("sort_order", "downloads")
                if (page > 1) addQueryParameter("start_index", ((page - 1) * CATALOG_PAGE + 1).toString())
            }.build()
            get(url).use { resp ->
                if (!resp.isSuccessful) throw GutenbergException(GutenbergException.Reason.HTTP, "HTTP ${resp.code} from $url", resp.code)
                val (books, hasNext) = parseCatalog(resp.body?.string().orEmpty(), catalog)
                    ?: throw GutenbergException(GutenbergException.Reason.BAD_RESPONSE, "Unexpected answer from $url")
                GutenbergPage(
                    results = if (langs.size > 1) books.filter { b -> b.languages.any { it in langs } } else books,
                    count = -1,
                    page = page,
                    hasNext = hasNext,
                )
            }
        }

    /**
     * EPUB laden. Zuerst in dest.part, erst nach der ZIP-Prüfung nach dest umbenannt.
     * onProgress bekommt 0..1 (nur wenn der Server die Größe nennt) und am Ende 1.
     */
    suspend fun downloadEpub(book: GutenbergBook, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val url = book.epubUrl?.toHttpUrlOrNull()
            ?: throw GutenbergException(GutenbergException.Reason.NO_EPUB, "Book ${book.id} has no EPUB")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        try {
            get(url).use { resp ->
                // Katalogtreffer können Hörbücher ohne EPUB sein; deren Adresse endet mit 404
                if (resp.code == 404) throw GutenbergException(GutenbergException.Reason.NO_EPUB, "No EPUB at $url", 404)
                if (!resp.isSuccessful) throw GutenbergException(GutenbergException.Reason.HTTP, "HTTP ${resp.code} from $url", resp.code)
                val body = resp.body ?: throw GutenbergException(GutenbergException.Reason.NOT_EPUB, "Empty response from $url")
                val total = body.contentLength()
                var done = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) { lastPercent = percent; onProgress((done.toFloat() / total).coerceAtMost(1f)) }
                            }
                        }
                    }
                }
            }
            if (!isZip(tmp)) throw GutenbergException(GutenbergException.Reason.NOT_EPUB, "Download from $url is not an EPUB")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
            onProgress(1f)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun get(url: HttpUrl, client: OkHttpClient = http): Response {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw GutenbergException(GutenbergException.Reason.NETWORK, e.message ?: e.javaClass.simpleName, cause = e)
        }
    }

    private fun isZip(f: File): Boolean = f.length() > 4 && f.inputStream().use { s ->
        val head = ByteArray(2)
        s.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }

    companion object {
        private const val USER_AGENT = "SimpleNarrator (Android)"
        private const val CATALOG_PAGE = 25
        private val BOOK_ID = Regex("/ebooks/(\\d+)\\.opds$")
        private val LANGUAGE_SUFFIX = Regex("^(.*\\S)\\s+\\(([A-Za-z][A-Za-z ]*)\\)$")

        /** Englische Sprachnamen ("German") zu ISO-Codes ("de"), so wie der Katalog sie an Titel hängt. */
        private val LANGUAGE_CODES: Map<String, String> by lazy {
            Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase() }
        }

        /**
         * Treffer und "weitere Seite vorhanden" aus einem OPDS-Suchergebnis von gutenberg.org; null, wenn es kein
         * Atom-Feed ist. Navigationseinträge (Autoren, Themen) fallen weg. Nicht englische Bücher tragen ihre
         * Sprache im Titel ("Die Verwandlung (German)"); ohne Angabe ist das Buch englisch.
         */
        internal fun parseCatalog(xml: String, catalog: HttpUrl): Pair<List<GutenbergBook>, Boolean>? {
            if ("<feed" !in xml) return null
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val books = doc.select("entry").mapNotNull { e ->
                val id = BOOK_ID.find(e.selectFirst("id")?.text()?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                var title = e.selectFirst("title")?.text().orEmpty().replace(Regex("\\s+"), " ").trim()
                var languages = listOf("en")
                LANGUAGE_SUFFIX.find(title)?.let { m ->
                    val code = LANGUAGE_CODES[m.groupValues[2].trim().lowercase()]
                    if (code != null) { title = m.groupValues[1].trimEnd(' ', ':'); languages = listOf(code) }
                    else languages = emptyList()
                }
                val author = e.selectFirst("content")?.text().orEmpty().replace(Regex("\\s+"), " ").trim()
                GutenbergBook(
                    id = id,
                    title = title,
                    authors = if (author.isEmpty()) emptyList() else listOf(GutenbergPerson(author)),
                    languages = languages,
                    downloadCount = 0,
                    epubUrl = catalog.resolve("/ebooks/$id.epub3.images").toString(),
                    coverUrl = catalog.resolve("/cache/epub/$id/pg$id.cover.medium.jpg").toString(),
                )
            }
            return books to doc.select("link[rel=next]").isNotEmpty()
        }

        private fun mime(key: String) = key.substringBefore(';').trim().lowercase()

        /**
         * Gutendex nennt pro MIME-Typ eine Adresse; Project Gutenberg selbst hat bis zu drei EPUB-Varianten.
         * Feste Reihenfolge: EPUB3 mit Bildern (".epub3.images", hat ein Navigationsdokument für saubere
         * Kapitelnamen und ein eingebettetes Cover), dann EPUB2 mit Bildern (".epub.images"), dann ohne Bilder
         * (".epub.noimages" oder andere). Bilder liest die App nicht vor, das Cover zeigt sie aber an.
         */
        internal fun pickEpub(formats: Map<String, String>): String? =
            formats.filterKeys { mime(it) == "application/epub+zip" }.values
                .filter { it.startsWith("http", ignoreCase = true) }
                .minByOrNull { epubRank(it) }

        private fun epubRank(url: String): Int {
            val u = url.lowercase()
            return when {
                ".epub3" in u || "-images-3.epub" in u -> 0
                ".epub.images" in u || "-images.epub" in u -> 1
                else -> 2
            }
        }
    }
}
