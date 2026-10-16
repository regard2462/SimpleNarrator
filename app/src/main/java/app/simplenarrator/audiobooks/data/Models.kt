package app.simplenarrator.audiobooks.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Ein in eine Datei eingebettetes Kapitel, Startzeit relativ zum Dateianfang. */
@Serializable
data class Mark(val title: String, val startMs: Long)

/** Eine Audiodatei eines Hörbuchs. */
@Serializable
data class Track(
    val uri: String,
    /** Pfad relativ zum Buchordner, dient zum Sortieren und zur Anzeige. */
    val path: String,
    val durationMs: Long,
    val size: Long = 0,
    val modified: Long = 0,
    /** Titel aus den Tags der Datei. */
    val title: String? = null,
    val marks: List<Mark> = emptyList(),
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** Ein Kapitel mit Zeiten über das ganze Buch gerechnet. */
@Serializable
data class Chapter(
    val title: String?,
    val startMs: Long,
    val endMs: Long,
    val track: Int,
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
data class Book(
    val id: String,
    /** Baum-URI des Bibliotheksordners. */
    val root: String,
    /** Pfad des Buchordners oder der Buchdatei relativ zum Bibliotheksordner. */
    val path: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val tracks: List<Track>,
    val chapters: List<Chapter>,
    val cover: String? = null,
    val coverKey: String? = null,
    val color: Int? = null,
    val addedAt: Long = 0,
) {
    @Transient
    val trackStarts: LongArray = LongArray(tracks.size).also { arr ->
        var acc = 0L
        tracks.forEachIndexed { i, t -> arr[i] = acc; acc += t.durationMs }
    }

    @Transient
    val durationMs: Long = tracks.sumOf { it.durationMs }

    /** Gesamtposition aus Dateiindex und Position in der Datei. */
    fun globalOf(trackIndex: Int, positionMs: Long): Long {
        if (tracks.isEmpty()) return 0
        val i = trackIndex.coerceIn(0, tracks.lastIndex)
        return trackStarts[i] + positionMs.coerceIn(0, tracks[i].durationMs.coerceAtLeast(0))
    }

    /** Dateiindex und Position in der Datei zu einer Gesamtposition. */
    fun locate(globalMs: Long): Pair<Int, Long> {
        if (tracks.isEmpty()) return 0 to 0L
        val g = globalMs.coerceIn(0, durationMs)
        var lo = 0
        var hi = tracks.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (trackStarts[mid] <= g) lo = mid else hi = mid - 1
        }
        // Am exakten Ende einer Datei, die nicht die letzte ist, zur nächsten wechseln.
        return lo to (g - trackStarts[lo])
    }

    /** Index des Kapitels, in dem die Gesamtposition liegt. */
    fun chapterAt(globalMs: Long): Int {
        if (chapters.isEmpty()) return -1
        var lo = 0
        var hi = chapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (chapters[mid].startMs <= globalMs) lo = mid else hi = mid - 1
        }
        return lo
    }
}

@Serializable
data class Bookmark(
    val id: Long,
    val positionMs: Long,
    val note: String = "",
    val createdAt: Long,
)

/** Alles, was zum Hören eines Buches gehört und beim Neueinlesen erhalten bleibt. */
@Serializable
data class BookState(
    val positionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val finished: Boolean = false,
    val finishedAt: Long = 0,
    /** 0 bedeutet Standardgeschwindigkeit aus den Einstellungen. */
    val speed: Float = 0f,
    val skipSilence: Boolean = false,
    /** Lautstärkeanhebung in dB. */
    val boostDb: Int = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    val listenedMs: Long = 0,
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val customCover: String? = null,
    val customColor: Int? = null,
    /** Pfad zum Wiederfinden beim Import einer Sicherung. */
    val pathHint: String? = null,
    /** Aus der Bibliothek entfernt; die Dateien bleiben, das Buch lässt sich wiederherstellen. */
    val hidden: Boolean = false,
    /** Aus dem Internet geladenes Titelbild, nur für Bücher ohne eigenes. */
    val webCover: String? = null,
    val webColor: Int? = null,
    /** Titel und Autor der letzten Online-Suche; ändern sie sich, wird neu gesucht. */
    val webQuery: String? = null,
    val webTriedAt: Long = 0,
    /** Online-Titelbild für dieses Buch von Hand entfernt: nicht mehr automatisch suchen. */
    val webBlocked: Boolean = false,
)

enum class Status { NEW, STARTED, FINISHED }

/** Buch und Hörstand zusammen, so wie die Oberfläche sie braucht. [webCovers]: Online-Titelbilder zeigen. */
data class Entry(val book: Book, val state: BookState, val webCovers: Boolean = true) {
    val id: String get() = book.id
    val title: String = state.title?.takeIf { it.isNotBlank() } ?: book.title
    val author: String? = state.author?.takeIf { it.isNotBlank() } ?: book.author
    val narrator: String? = state.narrator?.takeIf { it.isNotBlank() } ?: book.narrator
    /** Das Online-Titelbild, wenn es gerade angezeigt wird. */
    val webCover: String? = state.webCover.takeIf { webCovers && state.customCover == null && book.cover == null }
    val cover: String? = state.customCover ?: book.cover ?: webCover
    val color: Int? = when {
        state.customCover != null -> state.customColor
        book.cover != null -> book.color
        webCover != null -> state.webColor
        else -> null
    }
    val durationMs: Long = book.durationMs
    val positionMs: Long = state.positionMs.coerceIn(0, durationMs)
    val remainingMs: Long = (durationMs - positionMs).coerceAtLeast(0)
    val progress: Float = if (durationMs > 0) (positionMs.toDouble() / durationMs).toFloat() else 0f
    val status: Status = when {
        state.finished -> Status.FINISHED
        positionMs > 0 || state.lastPlayedAt > 0 -> Status.STARTED
        else -> Status.NEW
    }
    val chapterIndex: Int get() = book.chapterAt(positionMs)
}
