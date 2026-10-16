package app.simplenarrator.audiobooks.online

import java.text.Normalizer

/** Ein Titelbild aus einem Online-Katalog. */
data class CoverCandidate(
    /** Anzeigename des Katalogs, etwa „Audible“. */
    val source: String,
    val title: String,
    val authors: List<String>,
    /** Große Fassung zum Speichern. */
    val imageUrl: String,
    /** Kleine Fassung für die Auswahlliste. */
    val previewUrl: String,
)

/**
 * Entscheidet, ob ein Katalogeintrag zum Hörbuch passt. Automatisch übernommen wird nur, was eindeutig passt:
 * ein Titel mit fast denselben Wörtern und, wenn das Buch einen Autor hat, derselbe Autor.
 */
object CoverMatch {

    /** Wörter, die über Ausgabe und Medium sprechen, nicht über das Buch. */
    private val NOISE = setOf(
        "ungekurzt", "ungekuerzt", "gekurzt", "gekuerzt", "horbuch", "hoerbuch", "horspiel", "hoerspiel",
        "audiobook", "audio", "unabridged", "abridged", "lesung", "autorenlesung", "edition", "ausgabe",
        "cd", "mp3", "m4b", "folge", "teil", "band", "vol", "volume", "part",
    )

    /** Kleinschrift, Umlaute als ae/oe/ue, ß als ss, andere Akzente entfernt. */
    fun normalize(text: String): String {
        val s = text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        return Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    fun words(text: String): List<String> =
        normalize(text).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .map { w -> if (w.all(Char::isDigit)) w.trimStart('0').ifEmpty { "0" } else w }
            .filter { it !in NOISE }

    /** Fassungen eines Titels: ganz, ohne Klammern, Haupt- und Untertitel einzeln. */
    private fun variants(title: String): List<Set<String>> {
        val plain = title.replace(Regex("[(\\[{][^)\\]}]*[)\\]}]"), " ").trim()
        val parts = Regex("\\s[-–—:|]\\s|:\\s|\\s/\\s").split(plain).map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf(title, plain) + parts).map { words(it).toSet() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Ähnlichkeit zweier Titel von 0 bis 1: gemeinsame Wörter geteilt durch die Wörter des längeren. */
    fun titleSimilarity(a: String, b: String): Float {
        var best = 0f
        for (x in variants(a)) for (y in variants(b)) {
            val common = x.count { it in y }
            val s = common.toFloat() / maxOf(x.size, y.size)
            if (s > best) best = s
        }
        return best
    }

    /** Namensteile, die einen Autor ausmachen; Initialen und Füllwörter zählen nicht. */
    private fun nameWords(name: String): Set<String> =
        words(name).filter { it.length >= 3 && it !in setOf("und", "and", "von", "van", "der", "den", "gelesen", "read", "by", "mit", "with") }.toSet()

    fun authorMatches(author: String, candidates: List<String>): Boolean {
        val mine = nameWords(author)
        if (mine.isEmpty()) return false
        val theirs = candidates.flatMap { nameWords(it) }.toSet()
        return mine.any { it in theirs }
    }

    /** Ob ein Eintrag ohne Rückfrage übernommen werden darf. */
    fun accepts(title: String, author: String?, c: CoverCandidate): Boolean {
        val t = titleSimilarity(title, c.title)
        return if (!author.isNullOrBlank()) {
            t >= 0.75f && authorMatches(author, c.authors)
        } else {
            // Ohne Autor nur bei gleichem und nicht zu kurzem Titel; kurze Titel gibt es zu oft.
            t >= 0.999f && words(title).size >= 3 && c.authors.isNotEmpty()
        }
    }

    /**
     * Bester übernehmbarer Eintrag, bei gleichem Rang der zuerst gelieferte. Ohne Autor muss die Antwort
     * eindeutig sein: Alle Einträge mit diesem Titel nennen denselben Autor.
     */
    fun best(title: String, author: String?, list: List<CoverCandidate>): CoverCandidate? {
        val ok = list.filter { accepts(title, author, it) }
        val first = ok.maxByOrNull { titleSimilarity(title, it.title) } ?: return null
        if (author.isNullOrBlank()) {
            val name = first.authors.joinToString(" ")
            if (ok.any { !authorMatches(name, it.authors) }) return null
        }
        return first
    }

    /** Reihenfolge für die Auswahlliste: passende zuerst, sonst wie geliefert. */
    fun rank(title: String, author: String?, list: List<CoverCandidate>): List<CoverCandidate> =
        list.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<CoverCandidate>> {
                    val t = titleSimilarity(title, it.value.title)
                    val a = !author.isNullOrBlank() && authorMatches(author, it.value.authors)
                    (if (a) 1f else 0f) + t
                }.thenBy { it.index },
            )
            .map { it.value }
}
