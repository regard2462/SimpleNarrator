package app.simplenarrator.speech

/**
 * Schreibweisen, an denen espeak-ng scheitert (und damit Piper, Kokoro und Kitten, die ihre Aussprache von
 * espeak-ng bekommen), in sprechbare Form bringen. Mit piper/espeak-ng geprüft:
 *
 * - Stottern: "W-what" wird "double-u what", "N-nein" wird "En nein". Gelesen wird nur das Wort;
 *   ist der Anlauf selbst das ganze Wort ("I-I"), wird es mit Komma wiederholt.
 * - Gedankenstriche ohne Leerzeichen ("was--how", "war—wie") und "|" (Verszeilen) ergeben keine Pause;
 *   sie werden zu einem Gedankenstrich mit Leerzeichen, den der Normalisierer zur Kommapause macht.
 * - Interjektionen ohne Vokal ("Shhh", "Hmph", "Pst", "Brr") werden buchstabiert. Ersetzt werden sie durch
 *   Schreibweisen, die espeak-ng als Laut liest ("Sch", "Humph", "Pist", "Burr"); was sich nicht als Laut
 *   schreiben lässt (Schnarchen "Zzz", deutsches "Tss"), fällt weg.
 */
object Speakable {

    /**
     * [interjections]: auch Interjektionen umschreiben. Nur für Stimmen, die über espeak-ng lesen; Supertonic liest
     * die Buchstaben selbst und braucht keinen zusätzlichen Vokal ("Pist").
     */
    fun apply(text: String, lang: String, interjections: Boolean = true): String {
        var t = text
        t = DASHES.replace(t, " – ")
        t = t.replace(Regex("""-{2,}"""), "–")
        t = PIPE.replace(t, ", ")
        t = STUTTER.replace(t) { m -> stutter(m) ?: m.value }
        if (interjections) {
            val table = if (lang == "en") EN else DE
            for ((pattern, sound) in table) t = pattern.replace(t) { m -> withCase(m.value, sound) }
        }
        // Ist eine Interjektion weggefallen, bleibt kein Satzzeichen am Anfang stehen
        return t.replace(Regex("""^[\s,;:]+"""), "").replace(Regex("""[,;:]\s*(?=[,;:.!?])"""), "").trim()
    }

    /** "--" oder "—" zwischen zwei Wörtern (nicht zwischen Zahlen, dort ist es ein Bereich). */
    private val DASHES = Regex("""(?<=[^\s\d])\s*(?:-{2,}|—+)\s*(?=[^\s\d])""")
    private val PIPE = Regex("""\s*\|\s*""")

    /** Anlauf aus einem Buchstaben (oder "Wh", "Th", "Sh"), ein- oder mehrfach, dann das Wort. */
    private val STUTTER = Regex("""(?<![\p{L}\d-])(\p{L}h?)(?:-\1)*-(\p{L}+)""", RegexOption.IGNORE_CASE)

    private fun stutter(m: MatchResult): String? {
        val prefix = m.groupValues[1]
        val word = m.groupValues[2]
        if (!word.startsWith(prefix, ignoreCase = true)) return null
        if (word.equals(prefix, ignoreCase = true)) return "$word, $word"
        // Nur echtes Stottern: "N-nein", "b-b-but". "D-Dur" oder "T-Träger" sind Wörter und bleiben.
        if (!word[0].isLowerCase()) return null
        return if (prefix[0].isUpperCase()) word.replaceFirstChar { it.uppercaseChar() } else word
    }

    /** Weggelassene Interjektion samt ihrem Satzzeichen ("Zzz.", "Tss,"). */
    private fun dropped(body: String): Regex = Regex(
        """(?<![\p{L}\d])(?:$body)(?![\p{L}])[,;:.!?…]*\s*""",
        RegexOption.IGNORE_CASE,
    )

    private fun withCase(original: String, sound: String): String =
        if (sound.isNotEmpty() && original.first().isUpperCase()) sound.replaceFirstChar { it.uppercaseChar() } else sound

    /** Ganzes Wort; bei Wörtern aus "m" nicht direkt hinter einer Zahl ("5 mm", "3 hm"). */
    private fun word(body: String, afterNumberOk: Boolean = true): Regex = Regex(
        """(?<![\p{L}\d])""" + (if (afterNumberOk) "" else """(?<!\d\s)""") + "(?:$body)" + """(?![\p{L}])""",
        RegexOption.IGNORE_CASE,
    )

    // Reihenfolge zählt: zusammengesetzte Formen ("Mhm", "Hmpf") vor den einfachen ("Hm").
    private val EN: List<Pair<Regex, String>> = listOf(
        word("s?sh+", afterNumberOk = false) to "sch",
        word("ps+t") to "psst",
        word("h+m+p+[hf]+") to "humph",
        word("m+h+m+|m+-h+m+|h+m+-h+m+", afterNumberOk = false) to "hmm-hmm",
        word("h+m+", afterNumberOk = false) to "hmm",
        word("m{2,}h?", afterNumberOk = false) to "hmm",
        word("b+r{2,}") to "burr",
        word("g+r{2,}") to "gurr",
        word("pf+t*") to "pfuh",
        dropped("z{3,}") to "",
    )

    private val DE: List<Pair<Regex, String>> = listOf(
        word("ps+ch+t?") to "pischt",
        word("ps+t") to "pist",
        word("sch+(?:sch+)*") to "pischt",
        word("h+m+p+[fh]+") to "humpf",
        word("m+h+m+|m+-h+m+|h+m+-h+m+", afterNumberOk = false) to "hm-hm",
        word("h+m+", afterNumberOk = false) to "hm",
        word("m{2,}h?", afterNumberOk = false) to "hm",
        word("b+r{2,}") to "bürr",
        word("g+r{2,}") to "gürr",
        word("pf{2,}t?|pft") to "pah",
        dropped("ts{2,}") to "",
        dropped("z{3,}") to "",
    )
}
