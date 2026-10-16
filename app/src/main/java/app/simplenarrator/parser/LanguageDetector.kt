package app.simplenarrator.parser

/**
 * Leichte Spracherkennung über Funktionswörter. Reicht, um pro Absatz zwischen
 * Deutsch und Englisch zu wählen (gemischte Bücher, englische Zitate etc.).
 * Liefert null, wenn der Text zu kurz oder unentscheidbar ist.
 */
object LanguageDetector {
    private val de = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ein", "eine", "einer", "einem", "einen", "ich", "sie", "er",
        "es", "zu", "mit", "auf", "für", "von", "dem", "den", "des", "sich", "auch", "als", "wie", "aber", "noch",
        "nach", "bei", "wir", "oder", "dass", "daß", "ihr", "ihm", "ihn", "wenn", "nur", "war", "sind", "hat",
        "hatte", "schon", "doch", "wird", "über", "aus", "sehr", "kann", "keine", "mehr", "durch", "wurde", "beim",
    )
    private val en = setOf(
        "the", "and", "is", "not", "a", "i", "she", "he", "it", "to", "with", "on", "for", "of", "was", "are",
        "be", "this", "that", "have", "has", "you", "they", "but", "at", "from", "by", "were", "his", "her",
        "which", "there", "would", "their", "what", "when", "into", "than", "could", "been", "about", "them",
    )

    fun detect(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.size < 4) return null
        var d = 0; var e = 0
        for (w in words) { if (w in de) d++; if (w in en) e++ }
        if (d == e) return null
        return if (d > e) "de" else "en"
    }
}
