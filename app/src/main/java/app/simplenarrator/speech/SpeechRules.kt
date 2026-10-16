package app.simplenarrator.speech

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Ergebnis von [SpeechRules.apply]: ein Stück Sprechtext oder eine Pause. */
sealed class Segment {
    data class Speak(val text: String) : Segment()
    data class Pause(val ms: Int) : Segment()
}

/**
 * Eine Regelzeile, die nicht verwendet werden kann. [line] zählt ab 1, [text] ist die Zeile wie geschrieben,
 * [detail] die Meldung der Regex-Bibliothek (englisch, nur bei [Kind.INVALID_REGEX] und [Kind.INVALID_REPLACEMENT]).
 * Die Oberfläche übersetzt [kind] über die Texte sp_rule_error_*.
 */
data class RuleError(val line: Int, val text: String, val kind: Kind, val detail: String = "") {
    enum class Kind { MISSING_SEPARATOR, EMPTY_PATTERN, INVALID_REGEX, INVALID_REPLACEMENT }
}

/**
 * Erweitertes Aussprache-Wörterbuch. Eine Regel pro Zeile:
 *
 * - `wort=aussprache`: ganzes Wort, Groß-/Kleinschreibung egal, Umlaute zählen als Buchstaben (wie bisher).
 * - `*regex=ersatz`: regulärer Ausdruck (Java-Syntax), ohne Beachtung der Groß-/Kleinschreibung,
 *   `$1` bzw. `${name}` setzen Gruppen ein. `(?-i)` am Anfang macht die Regel schreibungsgenau.
 *   Ein `=` im Muster wird als `\=` geschrieben; Lookarounds wie `(?<=…)` und `(?=…)` gehen direkt.
 * - Ersatz leer oder `[skip]`: Fundstelle entfällt. `[pause]` (500 ms) bzw. `[pause:800]` fügen eine Pause ein.
 * - Zeilen mit `#` am Anfang sind Kommentare bzw. abgeschaltete Regeln, leere Zeilen zählen nicht.
 *
 * Leerzeichen am Rand des Musters werden entfernt (für ein Leerzeichen am Rand `\x20` schreiben). Der Ersatz
 * einer Regex-Regel gilt genau wie geschrieben, der einer Wort-Regel ohne Rand-Leerzeichen.
 * Mehrfache Leerzeichen im Ergebnis werden zu einem zusammengefasst.
 * `[pause]` und `[pause:N]` im Buchtext selbst werden ebenfalls als Pause gelesen.
 *
 * Fehlerhafte Zeilen landen in [errors] und werden übersprungen, es fliegt nie eine Ausnahme.
 */
class SpeechRules private constructor(private val rules: List<Rule>, val errors: List<RuleError>) {

    private class Rule(val pattern: Pattern, val replacement: String)

    /** Anzahl der wirksamen Regeln (ohne Kommentare und fehlerhafte Zeilen). */
    val size: Int get() = rules.size
    val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * Wendet alle Regeln der Reihe nach an und zerlegt den Text an Pausen. Benachbarte Sprechstücke werden
     * zusammengelegt, leere entfallen, Stücke nur aus Satzzeichen hängen am vorigen Sprechstück (so bleibt
     * der Satzpunkt bei "Er kam[pause]." vorn), Pausen liegen zwischen 1 und 5000 ms.
     */
    fun apply(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        val start = INLINE_PAUSE.replace(text.replace(MARK_START.toString(), "").replace(MARK_END.toString(), "")) { m ->
            marker(pauseMs(m.groupValues[1]))
        }
        var segs: List<Segment> = splitMarkers(start)
        for (rule in rules) {
            val out = ArrayList<Segment>(segs.size + 2)
            for (s in segs) {
                if (s !is Segment.Speak) { out += s; continue }
                val replaced = try {
                    rule.pattern.matcher(s.text).replaceAll(rule.replacement)
                } catch (e: Exception) {
                    s.text   // sollte nach der Prüfung in parse() nicht vorkommen; die Regel wird dann übergangen
                }
                if (replaced == s.text) out += s else out += splitMarkers(replaced)
            }
            segs = out
        }
        return tidy(segs)
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 500
        const val MAX_PAUSE_MS = 5000

        val EMPTY = SpeechRules(emptyList(), emptyList())

        // Pausenmarken im Arbeitstext: Zeichen aus dem privaten Unicode-Bereich, die in Büchern nicht vorkommen.
        private const val MARK_START = ''
        private const val MARK_END = ''
        private val MARKER = Regex("(\\d+)")

        private val INLINE_PAUSE = Regex("""\[\s*pause\s*(?::\s*(\d+)\s*(?:ms)?\s*)?]""", RegexOption.IGNORE_CASE)
        private val SKIP = Regex("""\[\s*skip\s*]""", RegexOption.IGNORE_CASE)
        private val WS = Regex("""\s+""")
        private val NAMED_GROUP = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")

        /** Wortzeichen für ganze Wörter; ausdrücklich Unicode, damit "Bär" nicht in "Eisbär" trifft. */
        private const val WORD = """[\p{L}\p{M}\p{N}_]"""

        /** Satzzeichen, vor die beim Zusammenfügen kein Leerzeichen gehört. */
        private const val NO_SPACE_BEFORE = ".,;:!?…)]}»«“”’'\""

        fun parse(raw: String): SpeechRules {
            val rules = ArrayList<Rule>()
            val errors = ArrayList<RuleError>()
            val plainIndex = HashMap<String, Int>()   // gleiches Wort zweimal: die spätere Zeile gilt (wie bisher)
            raw.lines().forEachIndexed { i, original ->
                // Nur vorn kürzen: ein Ersatz aus Leerzeichen am Zeilenende soll erhalten bleiben
                val line = original.trimStart()
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val lineNo = i + 1
                if (line.startsWith("*")) {
                    val body = line.substring(1)
                    val sep = regexSeparator(body)
                    if (sep < 0) { errors += RuleError(lineNo, original, RuleError.Kind.MISSING_SEPARATOR); return@forEachIndexed }
                    val pattern = body.substring(0, sep).trim()
                    if (pattern.isEmpty()) { errors += RuleError(lineNo, original, RuleError.Kind.EMPTY_PATTERN); return@forEachIndexed }
                    val compiled = try {
                        compileRegex(pattern)
                    } catch (e: PatternSyntaxException) {
                        errors += RuleError(lineNo, original, RuleError.Kind.INVALID_REGEX, e.description ?: e.message.orEmpty())
                        return@forEachIndexed
                    }
                    val replacement = prepareReplacement(body.substring(sep + 1), literal = false)
                    val problem = checkReplacement(compiled, replacement)
                    if (problem != null) {
                        errors += RuleError(lineNo, original, RuleError.Kind.INVALID_REPLACEMENT, problem)
                        return@forEachIndexed
                    }
                    rules += Rule(compiled, replacement)
                } else {
                    val sep = line.indexOf('=')
                    if (sep < 0) { errors += RuleError(lineNo, original, RuleError.Kind.MISSING_SEPARATOR); return@forEachIndexed }
                    val word = line.substring(0, sep).trim()
                    if (word.isEmpty()) { errors += RuleError(lineNo, original, RuleError.Kind.EMPTY_PATTERN); return@forEachIndexed }
                    val pattern = Pattern.compile(
                        "(?<!$WORD)" + Pattern.quote(word) + "(?!$WORD)",
                        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
                    )
                    val rule = Rule(pattern, prepareReplacement(line.substring(sep + 1), literal = true))
                    val earlier = plainIndex[word]
                    if (earlier != null) rules[earlier] = rule else { plainIndex[word] = rules.size; rules += rule }
                }
            }
            return SpeechRules(rules, errors)
        }

        /** Wandelt Regeldateien anderer Leseprogramme in dieses Format um, siehe [RuleImport]. */
        fun importRules(text: String): String = RuleImport.importRules(text)

        /**
         * Unicode-Wortklassen (\w, \b) wie auf Android. Die JVM braucht dafür UNICODE_CHARACTER_CLASS, Android (ICU)
         * kennt das Flag nicht und wirft IllegalArgumentException, arbeitet dort aber ohnehin mit Unicode-Klassen.
         */
        internal fun compileRegex(pattern: String): Pattern {
            val base = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            return try {
                Pattern.compile(pattern, base or Pattern.UNICODE_CHARACTER_CLASS)
            } catch (e: PatternSyntaxException) {
                throw e
            } catch (e: IllegalArgumentException) {
                Pattern.compile(pattern, base)
            }
        }

        /**
         * Position des Trennzeichens `=` in einer Regex-Zeile (ohne führendes `*`), sonst -1. Übergangen werden
         * maskierte Zeichen (`\=`), `\Q…\E`, Zeichenklassen (`[=]`) und Lookarounds (`(?=`, `(?<=`).
         */
        internal fun regexSeparator(s: String): Int {
            var i = 0
            var classDepth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> {
                        if (s.startsWith("\\Q", i)) {
                            val end = s.indexOf("\\E", i + 2)
                            if (end < 0) return -1
                            i = end + 2
                            continue
                        }
                        i += 2
                        continue
                    }
                    c == '[' -> classDepth++
                    c == ']' && classDepth > 0 -> classDepth--
                    c == '=' && classDepth == 0 -> {
                        val lookaround = s.startsWith("(?", i - 2) || s.startsWith("(?<", i - 3)
                        if (!lookaround) return i
                    }
                }
                i++
            }
            return -1
        }

        /**
         * Ersatztext vorbereiten: [skip] und Pausenmarken; einfache Regeln wörtlich und ohne Rand-Leerzeichen
         * (wie bisher), Regex-Regeln genau wie geschrieben (überzählige Leerzeichen fasst apply() später zusammen).
         */
        private fun prepareReplacement(raw: String, literal: Boolean): String {
            val base = if (!literal) raw else raw.trim().ifEmpty { if (raw.isEmpty()) "" else " " }
            val noSkip = SKIP.replace(base, "")
            val sb = StringBuilder()
            var last = 0
            for (m in INLINE_PAUSE.findAll(noSkip)) {
                val part = noSkip.substring(last, m.range.first)
                sb.append(if (literal) java.util.regex.Matcher.quoteReplacement(part) else part)
                sb.append(marker(pauseMs(m.groupValues[1])))
                last = m.range.last + 1
            }
            val rest = noSkip.substring(last)
            sb.append(if (literal) java.util.regex.Matcher.quoteReplacement(rest) else rest)
            return sb.toString()
        }

        /** Prüft Gruppenverweise im Ersatz an einem Muster mit gleich vielen (benannten) Gruppen. */
        private fun checkReplacement(p: Pattern, replacement: String): String? {
            val count = p.matcher("").groupCount()
            val names = NAMED_GROUP.findAll(p.pattern()).map { it.groupValues[1] }.distinct().toList()
            val dummy = buildString {
                names.forEach { append("(?<").append(it).append(">)") }
                repeat(count - names.size) { append("()") }
            }
            return try {
                Pattern.compile(dummy).matcher("").replaceFirst(replacement)
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }

        private fun pauseMs(digits: String): Int =
            if (digits.isEmpty()) DEFAULT_PAUSE_MS
            else (digits.toLongOrNull() ?: Long.MAX_VALUE).coerceIn(0, MAX_PAUSE_MS.toLong()).toInt()

        private fun marker(ms: Int) = "$MARK_START$ms$MARK_END"

        private fun splitMarkers(s: String): List<Segment> {
            if (s.indexOf(MARK_START) < 0) return if (s.isEmpty()) emptyList() else listOf(Segment.Speak(s))
            val out = ArrayList<Segment>()
            var last = 0
            for (m in MARKER.findAll(s)) {
                if (m.range.first > last) out += Segment.Speak(s.substring(last, m.range.first))
                out += Segment.Pause(m.groupValues[1].toInt())
                last = m.range.last + 1
            }
            if (last < s.length) out += Segment.Speak(s.substring(last))
            return out
        }

        private fun tidy(segs: List<Segment>): List<Segment> {
            val out = ArrayList<Segment>()
            for (s in segs) when (s) {
                is Segment.Pause -> {
                    val ms = s.ms.coerceIn(0, MAX_PAUSE_MS)
                    if (ms == 0) continue
                    val last = out.lastOrNull()
                    if (last is Segment.Pause) out[out.lastIndex] = Segment.Pause(minOf(MAX_PAUSE_MS, last.ms + ms))
                    else out += Segment.Pause(ms)
                }
                is Segment.Speak -> {
                    val t = s.text.replace(WS, " ").trim()
                    if (t.isEmpty()) continue
                    if (t.none { it.isLetterOrDigit() }) {
                        // Nur Satzzeichen: an den letzten Sprechtext hängen, sonst weglassen
                        val idx = out.indexOfLast { it is Segment.Speak }
                        if (idx >= 0) out[idx] = Segment.Speak(join((out[idx] as Segment.Speak).text, t))
                        continue
                    }
                    val last = out.lastOrNull()
                    if (last is Segment.Speak) out[out.lastIndex] = Segment.Speak(join(last.text, t))
                    else out += Segment.Speak(t)
                }
            }
            return out
        }

        private fun join(a: String, b: String): String = if (b[0] in NO_SPACE_BEFORE) a + b else "$a $b"
    }
}
