package app.simplenarrator.parser

object TextCleaner {
    /** Kaputte Kodierung reparieren, Whitespace vereinheitlichen, typografische Sonderzeichen in sprechbare Form bringen. */
    fun normalize(s: String): String = TextRepair.repair(s)
        .replace('\u00A0', ' ')
        .replace('\u00AD', ' ')        // weiches Trennzeichen
        .replace("\u2026", "...")
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("\\s*\\n\\s*"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    /**
     * PDF-Seiten säubern: wiederkehrende Kopf-/Fußzeilen und Seitenzahlen entfernen,
     * Zeilen zu Absätzen zusammenfügen, Silbentrennung am Zeilenende auflösen.
     * Rückgabe: pro Seite ein Text, Absätze durch "\n\n" getrennt.
     */
    fun cleanPdfPages(pages: List<String>): List<String> {
        val pageLines = pages.map { p -> p.lines().map { it.trim() }.filter { it.isNotBlank() } }
        val firstKeys = pageLines.mapNotNull { it.firstOrNull()?.let(::lineKey) }
        val lastKeys = pageLines.mapNotNull { it.lastOrNull()?.let(::lineKey) }
        val repeatedFirst = repeated(firstKeys)
        val repeatedLast = repeated(lastKeys)

        return pageLines.map { lines ->
            val kept = lines.filterIndexed { i, l ->
                val key = lineKey(l)
                val pageNumber = l.matches(Regex("^[\\divxlcIVXLC]{1,5}$")) ||
                        l.matches(Regex("^(Seite|Page|S\\.)\\s*\\d+.*$", RegexOption.IGNORE_CASE)) ||
                        l.matches(Regex("^-?\\s*\\d+\\s*-?$"))
                !(pageNumber ||
                        (i == 0 && key in repeatedFirst) ||
                        (i == lines.lastIndex && key in repeatedLast))
            }
            removeFootnoteMarks(joinLines(kept))
        }
    }

    /** Fußnotenziffer am Wort ("zeigte12 und") oder hinter dem Satzzeichen ("Ende.3 Danach"). */
    private val FOOTNOTE_AFTER_WORD = Regex("""(?<=\p{Ll}{4})\d{1,3}(?=[\s,.;:!?)]|$)""")
    private val FOOTNOTE_AFTER_PUNCT = Regex("""(?<=\p{Ll}{4}[.,;:!?])\d{1,3}(?=\s+\p{Lu})""")

    /**
     * Hochgestellte Fußnotenziffern verliert PDFBox als Hochstellung und hängt sie ohne Leerzeichen an; gelesen
     * würde dann "zeigte zwölf". Nur hinter mindestens vier Kleinbuchstaben, damit "m2", "mp3" oder "A4" bleiben.
     */
    fun removeFootnoteMarks(s: String): String =
        FOOTNOTE_AFTER_PUNCT.replace(FOOTNOTE_AFTER_WORD.replace(s, ""), "")

    private fun repeated(keys: List<String>): Set<String> =
        keys.groupingBy { it }.eachCount().filter { it.value >= 3 && it.key.length > 3 }.keys

    /** Zeile ohne Ziffern (damit "Kapitel 3" und "Kapitel 4" als gleiche Kopfzeile gelten). */
    private fun lineKey(l: String) = l.replace(Regex("\\d+"), "#").lowercase()

    /** Zeilen → Absätze. */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        var prevLen = 0
        for (raw in lines) {
            val line = raw.trim()
            if (sb.isEmpty()) { sb.append(line); prevLen = line.length; continue }
            if (sb.endsWith("-") && !sb.endsWith(" -") && line.firstOrNull()?.isLowerCase() == true) {
                sb.setLength(sb.length - 1)          // Silbentrennung: "Ge-" + "schichte"
                sb.append(line)
            } else {
                val prevEndsSentence = sb.last() in ".!?:\"»“”)"
                val looksLikeHeading = line.length < 60 && line == line.uppercase() && line.any { it.isLetter() }
                val paragraphBreak = (prevEndsSentence && prevLen < 55) || looksLikeHeading || line.matches(Regex("^(Kapitel|Chapter|Teil|Part)\\s+\\S+.*"))
                sb.append(if (paragraphBreak) "\n\n" else " ")
                sb.append(line)
            }
            prevLen = line.length
        }
        return sb.toString()
    }
}
