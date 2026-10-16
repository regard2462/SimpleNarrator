package app.simplenarrator.speech

/** Ein Abschnitt eines Satzes: wörtliche Rede ([dialogue] = true) oder Erzähltext. */
data class Run(val text: String, val dialogue: Boolean)

/**
 * Erkennt wörtliche Rede, damit eine zweite Stimme sie lesen kann.
 *
 * Eingabe ist ein Absatz als Liste von Sätzen; ein Zitat darf über mehrere Sätze gehen. Der Zustand
 * (offene Anführungszeichen) reicht über die Sätze des Absatzes und endet mit dem Absatz; ein am Absatzende
 * offenes Zitat ist erlaubt (im Englischen beginnt dann der nächste Absatz mit einem neuen öffnenden Zeichen).
 *
 * Erkannt werden „…“ und ‚…‘ (deutsch), »…« und «…« in beiden Richtungen sowie ›…‹, “…” und ‘…’ (englisch),
 * ”…” (schwedisch) und gerade Anführungszeichen " (wechseln jeweils zwischen auf und zu) und ' (wie ‘…’).
 * Apostrophe (don’t, Peter’s, geht’s, ’n’, ’90s, the boys’ toys) beenden kein Zitat.
 *
 * Die Anführungszeichen gehören zur wörtlichen Rede, Begleitsätze („…“, sagte er) zum Erzähltext.
 * Kurze Anführungen mitten im Satz ohne Satzzeichen (Er las den »Spiegel«) gelten als Titel oder Begriff
 * und bleiben Erzähltext. Die Texte aller Runs eines Satzes ergeben zusammen genau den Satz.
 */
object Dialogue {

    /** Für jeden Satz die Runs in Lesereihenfolge. Leere Sätze liefern eine leere Liste. */
    fun detect(sentences: List<String>): List<List<Run>> {
        val stack = ArrayList<Open>()
        return sentences.map { sentence(it, stack) }
    }

    /** Bequemlichkeit für einen einzelnen Satz ohne Absatzzusammenhang. */
    fun detect(sentence: String): List<Run> = detect(listOf(sentence)).first()

    /** Ein offenes Anführungszeichen: welche Zeichen es schließen, und wo es im aktuellen Satz begann (-1: früher). */
    private class Open(val closers: String, var openedAt: Int)

    private const val APOSTROPHE = '’'
    private const val SINGLE_LEFT = '‘'
    private const val STRAIGHT_SINGLE = '\''
    private const val STRAIGHT_DOUBLE = '"'

    /** Zeichen, nach denen ein öffnendes Anführungszeichen stehen kann. */
    private const val BEFORE_OPENER = "([{—–-/:„“\"»«›‹‚‘'"

    /** Satzzeichen, die eine Anführung als echte Rede kennzeichnen. */
    private const val SPEECH_PUNCT = ".!?,;:…"

    /** Verkürzte Wörter mit Apostroph am Anfang ('n', 'em, 'til …), die kein Zitat öffnen. */
    private val ELISIONS = setOf(
        "n", "em", "til", "tis", "twas", "twere", "twill", "twould", "cause", "cos", "coz", "bout", "round",
        "kay", "scuse", "nuff", "neath", "gainst", "fraid",
    )

    private fun sentence(s: String, stack: ArrayList<Open>): List<Run> {
        if (s.isEmpty()) return emptyList()
        for (o in stack) o.openedAt = -1
        val flags = BooleanArray(s.length)
        for (i in s.indices) {
            val inBefore = stack.isNotEmpty()
            val c = s[i]
            val closeAt = closingIndex(c, s, i, stack)
            if (closeAt >= 0) {
                val opened = stack[closeAt].openedAt
                while (stack.size > closeAt) stack.removeAt(stack.lastIndex)
                flags[i] = true
                if (stack.isEmpty() && opened >= 0 && isTermQuote(s, opened, i)) {
                    for (k in opened..i) flags[k] = false
                }
                continue
            }
            val closers = openerFor(c, s, i)
            if (closers != null) stack += Open(closers, i)
            flags[i] = inBefore || stack.isNotEmpty()
        }
        return buildRuns(s, flags)
    }

    /** Index des offenen Eintrags, den dieses Zeichen schließt, sonst -1. */
    private fun closingIndex(c: Char, s: String, i: Int, stack: List<Open>): Int {
        if (stack.isEmpty()) return -1
        for (k in stack.indices.reversed()) {
            if (c !in stack[k].closers) continue
            // ’ und ' schließen nur, wenn sie kein Apostroph sind
            if ((c == APOSTROPHE || c == STRAIGHT_SINGLE) && !looksLikeSingleCloser(c, s, i)) return -1
            return k
        }
        return -1
    }

    /** Schließende Zeichen, falls c hier ein Zitat öffnet, sonst null. */
    private fun openerFor(c: Char, s: String, i: Int): String? = when (c) {
        '„' -> "“”"
        '‚' -> "‘’"
        '“' -> "”"
        '»' -> "«"
        '«' -> "»"
        '›' -> "‹"
        '‹' -> "›"
        '”' -> if (atWordStart(s, i)) "”" else null
        STRAIGHT_DOUBLE -> "\""
        SINGLE_LEFT -> if (looksLikeSingleOpener(s, i)) "’" else null
        STRAIGHT_SINGLE -> if (looksLikeSingleOpener(s, i)) "'" else null
        else -> null
    }

    private fun atWordStart(s: String, i: Int): Boolean {
        val prev = s.getOrNull(i - 1)
        val next = s.getOrNull(i + 1) ?: return false
        return (prev == null || prev.isWhitespace() || prev in BEFORE_OPENER) && !next.isWhitespace()
    }

    private fun looksLikeSingleOpener(s: String, i: Int): Boolean {
        if (!atWordStart(s, i)) return false
        val next = s[i + 1]
        if (next.isDigit()) return false                      // ’90s
        val word = s.substring(i + 1).takeWhile { it.isLetter() }.lowercase()
        return word !in ELISIONS
    }

    private fun looksLikeSingleCloser(c: Char, s: String, i: Int): Boolean {
        val prev = s.getOrNull(i - 1) ?: return false
        if (prev.isWhitespace()) return false
        val next = s.getOrNull(i + 1)
        if (prev.isLetter() && next != null && next.isLetter()) return false   // don’t, Peter’s
        // the boys’ toys: Genitiv-Apostroph, wenn später im Satz noch ein passendes Schlusszeichen kommt
        if ((prev == 's' || prev == 'S') && next != null && next.isWhitespace()) {
            for (k in i + 1 until s.length) {
                if (s[k] != c) continue
                val p = s[k - 1]
                val n = s.getOrNull(k + 1)
                if (!p.isWhitespace() && !(p.isLetter() && n != null && n.isLetter())) return false
            }
        }
        return true
    }

    /** Kurze Anführung mitten im Satz ohne Satzzeichen: Titel, Begriff oder Zitatwort, keine Rede. */
    private fun isTermQuote(s: String, open: Int, close: Int): Boolean {
        val inner = s.substring(open + 1, close)
        if (inner.any { it in SPEECH_PUNCT }) return false
        val words = inner.split(' ', '\t', '\n').count { it.isNotBlank() }
        if (words == 0 || words > 3) return false
        val before = s.substring(0, open).trimEnd().lastOrNull() ?: return false
        return before.isLetterOrDigit()
    }

    private fun buildRuns(s: String, flags: BooleanArray): List<Run> {
        val raw = ArrayList<Run>()
        var start = 0
        for (i in 1..s.length) {
            if (i == s.length || flags[i] != flags[start]) {
                raw += Run(s.substring(start, i), flags[start])
                start = i
            }
        }
        // Runs ohne Buchstaben und Ziffern (Leerzeichen, Gedankenstrich, Satzpunkt) hängen am Nachbarn
        val merged = ArrayList<Run>()
        var pending = ""
        for (r in raw) {
            if (r.text.none { it.isLetterOrDigit() }) {
                if (merged.isEmpty()) pending += r.text
                else merged[merged.lastIndex] = merged.last().copy(text = merged.last().text + r.text)
                continue
            }
            val text = pending + r.text
            pending = ""
            val last = merged.lastOrNull()
            if (last != null && last.dialogue == r.dialogue) merged[merged.lastIndex] = last.copy(text = last.text + text)
            else merged += Run(text, r.dialogue)
        }
        if (pending.isNotEmpty()) merged += Run(pending, raw.first().dialogue)
        return merged
    }
}
