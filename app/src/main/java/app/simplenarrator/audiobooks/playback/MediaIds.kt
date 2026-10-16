package app.simplenarrator.audiobooks.playback

/**
 * Kennungen für Media3:
 * - "book:<id>" spielt ein Buch ab dem gespeicherten Stand
 * - "book:<id>@<ms>" spielt ein Buch ab einer Gesamtposition
 * - "track:<id>:<index>" eine einzelne Datei eines Buches
 * - "node:recent", "node:all" Ordner für Android Auto
 */
object MediaIds {
    const val ROOT = "node:root"
    const val RECENT = "node:recent"
    const val ALL = "node:all"
    const val RESUME = "node:resume"

    fun book(id: String, startMs: Long? = null): String = if (startMs == null) "book:$id" else "book:$id@$startMs"
    fun track(id: String, index: Int): String = "track:$id:$index"

    data class BookRequest(val id: String, val startMs: Long?)

    fun parseBook(mediaId: String): BookRequest? {
        if (!mediaId.startsWith("book:")) return null
        val rest = mediaId.removePrefix("book:")
        val at = rest.indexOf('@')
        return if (at < 0) BookRequest(rest, null) else BookRequest(rest.substring(0, at), rest.substring(at + 1).toLongOrNull())
    }

    /** Buchkennung aus einer Datei-Kennung. */
    fun bookOfTrack(mediaId: String?): String? {
        if (mediaId == null || !mediaId.startsWith("track:")) return null
        return mediaId.removePrefix("track:").substringBeforeLast(':')
    }

    fun trackIndex(mediaId: String?): Int? = mediaId?.takeIf { it.startsWith("track:") }?.substringAfterLast(':')?.toIntOrNull()
}
