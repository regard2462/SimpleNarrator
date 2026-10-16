package app.simplenarrator.data

import kotlinx.serialization.Serializable

/** Ein vorlesbarer Satz. lang ist "de" oder "en" und entscheidet über die Stimme. */
@Serializable
data class Sentence(val text: String, val lang: String)

@Serializable
data class Paragraph(val sentences: List<Sentence>, val isHeading: Boolean = false)

@Serializable
data class Chapter(val title: String, val paragraphs: List<Paragraph>) {
    val sentenceCount: Int get() = paragraphs.sumOf { it.sentences.size }

    /** Satz per fortlaufendem Index innerhalb des Kapitels (über Absatzgrenzen hinweg). */
    fun sentenceAt(index: Int): Sentence? {
        var i = index
        for (p in paragraphs) {
            if (i < p.sentences.size) return p.sentences[i]
            i -= p.sentences.size
        }
        return null
    }

    /** Ist der Satz der letzte seines Absatzes? (längere Pause danach) */
    fun endsParagraph(index: Int): Boolean {
        var i = index
        for (p in paragraphs) {
            if (i < p.sentences.size) return i == p.sentences.lastIndex
            i -= p.sentences.size
        }
        return false
    }

    /** Absatz und Position darin zum fortlaufenden Satzindex. */
    fun locate(index: Int): Pair<Int, Int>? {
        var i = index
        paragraphs.forEachIndexed { pi, p ->
            if (i < p.sentences.size) return pi to i
            i -= p.sentences.size
        }
        return null
    }

    /** Ist der Satz der erste eines Absatzes? (längere Pause beim Vorlesen) */
    fun startsParagraph(index: Int): Boolean {
        var i = index
        for (p in paragraphs) {
            if (i == 0) return true
            if (i < p.sentences.size) return false
            i -= p.sentences.size
        }
        return false
    }
}

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val sourceType: String,          // epub | pdf | txt | text (eingefügt) | web (Artikel)
    val coverFile: String? = null,   // Dateiname im Buchordner
    val chapters: List<Chapter>,
    val addedAt: Long,
    val sourceUrl: String? = null,   // Herkunft bei Webartikeln; fehlt in älteren book.json
) {
    val totalSentences: Int get() = chapters.sumOf { it.sentenceCount }
}

@Serializable
data class Progress(val chapter: Int = 0, val sentence: Int = 0, val updatedAt: Long = 0)

data class Position(val chapter: Int, val sentence: Int)

/** Rohdaten aus den Parsern, bevor Sätze/Sprache bestimmt werden. */
data class RawParagraph(val text: String, val isHeading: Boolean = false)

data class ParsedBook(
    val title: String,
    val author: String,
    val coverFile: String?,
    val chapters: List<Chapter>,
)
