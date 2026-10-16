package app.simplenarrator.parser

/**
 * Ersatztitel für Kapitel ohne eigene Überschrift. Sie gehören zum Buch und stehen deshalb in der
 * Sprache des Buchtexts, nicht in der Sprache der Oberfläche.
 */
object FallbackTitles {
    fun section(lang: String): String = if (lang == "en") "Section" else "Abschnitt"
    fun section(lang: String, n: Int): String = "${section(lang)} $n"
    fun pages(lang: String, from: Int, to: Int): String = (if (lang == "en") "Pages" else "Seiten") + " $from–$to"
    fun beginning(lang: String): String = if (lang == "en") "Beginning" else "Anfang"
    fun continued(lang: String, title: String): String = "$title (${if (lang == "en") "continued" else "Fortsetzung"})"
}
