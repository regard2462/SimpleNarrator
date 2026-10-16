package app.simplenarrator.audiobooks.scan

import app.simplenarrator.audiobooks.data.Chapter
import app.simplenarrator.audiobooks.data.Track

/** Tags einer Audiodatei. */
data class Tags(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val year: String? = null,
)

data class BookMeta(val title: String, val author: String?, val narrator: String?, val year: String?)

/** Reine Regeln, wie aus Dateien und Tags ein Buch wird. */
object BookBuilder {

    fun meta(candidate: Candidate, tags: List<Tags>): BookMeta {
        val album = Names.mostCommon(tags.map { it.album?.let(Names::stripDiscSuffix) })
        val tagAuthor = Names.mostCommon(tags.map { it.albumArtist }) ?: Names.mostCommon(tags.map { it.artist })
        val composer = Names.mostCommon(tags.map { it.composer })
        val year = Names.mostCommon(tags.map { Names.plausibleYear(it.year) })

        val nameTitle = Names.titleFromName(candidate.name)
        val split = Names.splitAuthorTitle(nameTitle)

        var title = when {
            !album.isNullOrBlank() -> album
            candidate.singleFile && tags.size == 1 && !tags[0].title.isNullOrBlank() -> tags[0].title!!
            tagAuthor == null && candidate.parentName == null && split != null -> split.second
            else -> nameTitle
        }
        val author = when {
            !tagAuthor.isNullOrBlank() -> tagAuthor
            candidate.parentName != null -> candidate.parentName
            split != null -> split.first
            else -> null
        }
        // "Autor - Titel" im Album-Tag kürzen, wenn der Autor schon bekannt ist
        if (author != null) {
            val s = Names.splitAuthorTitle(title)
            if (s != null && s.first.equals(author, ignoreCase = true)) title = s.second
        }
        val narrator = composer?.takeIf { author == null || !it.equals(author, ignoreCase = true) }
        return BookMeta(title.trim(), author?.trim(), narrator?.trim(), year)
    }

    /**
     * Kapitel über das ganze Buch: eingebettete Marken einer Datei werden zu Kapiteln,
     * sonst ist jede Datei ein Kapitel.
     */
    fun chapters(tracks: List<Track>): List<Chapter> {
        val useTags = useTagTitles(tracks)
        val out = ArrayList<Chapter>()
        var start = 0L
        tracks.forEachIndexed { i, t ->
            val duration = t.durationMs.coerceAtLeast(0)
            val marks = t.marks.filter { it.startMs in 0 until duration.coerceAtLeast(1) }.sortedBy { it.startMs }
            val fallbackTitle = if (useTags) t.title else Names.titleFromFileName(t.fileName)
            if (marks.isEmpty() || (marks.size == 1 && marks[0].title.isBlank())) {
                out += Chapter(fallbackTitle, start, start + duration, i)
            } else {
                if (marks[0].startMs > 1000) out += Chapter(fallbackTitle, start, start + marks[0].startMs, i)
                marks.forEachIndexed { j, m ->
                    val end = if (j + 1 < marks.size) marks[j + 1].startMs else duration
                    out += Chapter(m.title.ifBlank { null }, start + m.startMs, start + end, i)
                }
            }
            start += duration
        }
        return out
    }

    /** Titel-Tags nur verwenden, wenn die meisten Dateien unterschiedliche Titel tragen. */
    fun useTagTitles(tracks: List<Track>): Boolean {
        if (tracks.size == 1) return !tracks[0].title.isNullOrBlank()
        val titles = tracks.mapNotNull { it.title?.trim()?.takeIf { s -> s.isNotEmpty() } }
        if (titles.size * 2 < tracks.size) return false
        return titles.toSet().size * 2 > tracks.size
    }
}
