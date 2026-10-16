package app.simplenarrator.audiobooks.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/** Katalogregion: deutschsprachige Nutzer fragen Audible.de und den deutschen Apple-Katalog. */
enum class Market(val audibleHost: String, val appleCountry: String) {
    DE("api.audible.de", "DE"),
    US("api.audible.com", "US");

    companion object {
        fun forLanguage(language: String): Market = if (language == "de") DE else US
    }
}

/**
 * Adressen und Antworten der drei Kataloge. Gesendet werden nur Suchwörter (Titel und Autor), kein Schlüssel,
 * keine Kennung des Geräts. Alle drei liefern quadratische oder hochformatige JPEG-Bilder ohne Anmeldung.
 */
object CoverSources {

    const val AUDIBLE = "Audible"
    const val APPLE = "Apple Books"
    const val OPEN_LIBRARY = "Open Library"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    // ---------------------------------------------------------------- Audible

    fun audibleUrl(market: Market, title: String, author: String?): String {
        val q = StringBuilder("https://${market.audibleHost}/1.0/catalog/products?num_results=10&products_sort_by=Relevance")
            .append("&response_groups=contributors,media,product_attrs&image_sizes=500,1000")
            .append("&title=").append(enc(title.take(150)))
        if (!author.isNullOrBlank()) q.append("&author=").append(enc(author.take(80)))
        return q.toString()
    }

    fun audibleSearchUrl(market: Market, query: String): String =
        "https://${market.audibleHost}/1.0/catalog/products?num_results=20&products_sort_by=Relevance" +
            "&response_groups=contributors,media,product_attrs&image_sizes=500,1000&keywords=" + enc(query.take(200))

    fun parseAudible(body: String): List<CoverCandidate> {
        val root = parse(body) ?: return emptyList()
        return root.arr("products").mapNotNull { p ->
            // Podcastfolgen tragen oft Buchtitel, sind aber keine Hörbücher
            val delivery = p.str("content_delivery_type").orEmpty()
            if (delivery.startsWith("Podcast") || p.str("content_type") == "Podcast") return@mapNotNull null
            val images = p.obj("product_images") ?: return@mapNotNull null
            val big = images.str("1000") ?: images.str("500") ?: return@mapNotNull null
            val title = p.str("title") ?: return@mapNotNull null
            val subtitle = p.str("subtitle")
            CoverCandidate(
                source = AUDIBLE,
                title = if (subtitle.isNullOrBlank()) title else "$title: $subtitle",
                authors = p.arr("authors").mapNotNull { it.str("name") },
                imageUrl = big,
                previewUrl = images.str("500") ?: big,
            )
        }
    }

    // ---------------------------------------------------------------- Apple Books

    fun appleUrl(market: Market, query: String, limit: Int = 10): String =
        "https://itunes.apple.com/search?media=audiobook&entity=audiobook&country=${market.appleCountry}&limit=$limit&term=" + enc(query.take(200))

    fun parseApple(body: String): List<CoverCandidate> {
        val root = parse(body) ?: return emptyList()
        return root.arr("results").mapNotNull { r ->
            val art = r.str("artworkUrl100") ?: r.str("artworkUrl60") ?: return@mapNotNull null
            val title = r.str("collectionName") ?: r.str("trackName") ?: return@mapNotNull null
            CoverCandidate(
                source = APPLE,
                title = title,
                authors = r.str("artistName")?.split(Regex("\\s*(?:,|&| und | and )\\s*"))?.filter { it.isNotBlank() }.orEmpty(),
                imageUrl = resizeApple(art, 1000),
                previewUrl = resizeApple(art, 300),
            )
        }
    }

    /** Apple liefert Vorschaubilder wie …/100x100bb.jpg; die Größe steckt im letzten Pfadteil. */
    fun resizeApple(url: String, side: Int): String =
        url.replace(Regex("/\\d+x\\d+(bb|cc)?(-\\d+)?\\.(jpg|png|webp)$"), "/${side}x${side}bb.jpg")

    // ---------------------------------------------------------------- Open Library

    fun openLibraryUrl(query: String, limit: Int = 10): String =
        "https://openlibrary.org/search.json?fields=title,subtitle,author_name,cover_i&limit=$limit&q=" + enc(query.take(200))

    fun parseOpenLibrary(body: String): List<CoverCandidate> {
        val root = parse(body) ?: return emptyList()
        return root.arr("docs").mapNotNull { d ->
            val id = ((d as? JsonObject)?.get("cover_i") as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
            val title = d.str("title") ?: return@mapNotNull null
            CoverCandidate(
                source = OPEN_LIBRARY,
                title = d.str("subtitle")?.let { "$title: $it" } ?: title,
                authors = d.arr("author_name").mapNotNull { (it as? JsonPrimitive)?.content },
                imageUrl = "https://covers.openlibrary.org/b/id/$id-L.jpg",
                previewUrl = "https://covers.openlibrary.org/b/id/$id-M.jpg",
            )
        }
    }

    // ---------------------------------------------------------------- JSON-Hilfen

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()
    private fun JsonElement.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
