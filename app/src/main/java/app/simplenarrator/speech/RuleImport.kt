package app.simplenarrator.speech

/**
 * Übernimmt Ersetzungsregeln anderer Vorlese-Apps in das Format von [SpeechRules].
 *
 * Unterstützt:
 * 1. Librera Reader, Einstellung "TTS Replacements" als JSON-Objekt (`{"schlüssel":"wert", …}`, auch mit
 *    einfachen Anführungszeichen wie in Libreras Voreinstellung) oder eine ganze Librera-Sicherung, die das
 *    Feld `lineTTSReplacements3` enthält.
 * 2. Regeldateien (.txt) von @Voice Aloud Reader, die auch Librera als Wörterbuch liest: eine Regel pro Zeile,
 *    `"text" "ersatz"` für einfache Regeln, `*"regex" "ersatz"` für reguläre Ausdrücke, `#` für Kommentare.
 *    Die Schreibweise `"text" -> "ersatz"` aus Libreras Hilfeseite geht ebenso.
 *
 * Bedeutung wie bei Librera: einfache Regeln ersetzen genau diesen Text (auch innerhalb von Wörtern,
 * schreibungsgenau), reguläre Ausdrücke sind schreibungsgenau, solange sie nicht selbst `(?i)` setzen.
 * Beides wird deshalb zu `*(?-i)…`-Regeln. `#` vor dem Schlüssel schaltet die Regel ab, das bleibt so.
 * Der Befehl `ttsPAUSE` wird zu `[pause]`. Regeln mit `ttsSKIP`, `ttsSTOP` oder `ttsNEXT` (Satz bzw. Seite
 * überspringen, Vorlesen anhalten), Libreras HTML-Regeln (`<…>`) und Schlüssel auf "256" (bei Librera
 * abgeschaltet) haben hier keine Entsprechung und werden als abgeschaltete Zeile (`#…`) übernommen.
 * Zeilen, die schon im eigenen Format sind, bleiben unverändert.
 */
object RuleImport {

    private val QUOTED_RULE = Regex("""^(\*?)\s*"(.*?)"\s*(?:->|=>|=)?\s*"(.*)"\s*$""")
    private val UNSUPPORTED_COMMAND = Regex("ttsSKIP|ttsSTOP|ttsNEXT")
    private const val PAUSE_COMMAND = "ttsPAUSE"

    /** Zeichen, die in einem regulären Ausdruck maskiert werden müssen (plus `=` als Trennzeichen). */
    private const val REGEX_META = "\\^$.|?*+()[]{}="

    fun importRules(text: String): String {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.startsWith("{")) {
            val obj = LenientJson.parseObject(trimmed)
            if (obj != null) {
                val nested = obj.firstOrNull { it.first == "lineTTSReplacements3" }?.second
                val entries = nested?.let { LenientJson.parseObject(it.trim()) } ?: obj
                return entries.joinToString("\n") { (k, v) -> fromLibreraKey(k, v) }
            }
        }
        return text.lines().joinToString("\n") { importLine(it) }
    }

    private fun importLine(line: String): String {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return t
        val m = QUOTED_RULE.matchEntire(t) ?: return line   // schon im eigenen Format oder unbekannt
        return fromLibreraKey(m.groupValues[2], m.groupValues[3], forceRegex = m.groupValues[1] == "*")
    }

    /** Ein Schlüssel/Wert-Paar in Libreras Schreibweise (`*` = Regex, `#` = abgeschaltet) umwandeln. */
    private fun fromLibreraKey(rawKey: String, rawValue: String, forceRegex: Boolean = false): String {
        var key = rawKey
        var disabled = false
        if (key.startsWith("#")) { disabled = true; key = key.substring(1) }
        var regex = forceRegex
        if (key.startsWith("*")) { regex = true; key = key.substring(1) }
        val value = rawValue.replace('\n', ' ').replace('\r', ' ')
        val system = key.startsWith("<") && key.endsWith(">")
        if (key.endsWith("256")) disabled = true
        if (system || UNSUPPORTED_COMMAND.containsMatchIn(value)) disabled = true

        val rule = if (regex) {
            val pattern = protectEdges(escapeSeparators(key))
            val withCase = if (pattern.startsWith("(?i)")) pattern.removePrefix("(?i)") else "(?-i)$pattern"
            "*$withCase=" + value.replace(PAUSE_COMMAND, "[pause]")
        } else {
            val quoted = java.util.regex.Matcher.quoteReplacement(value)
            "*(?-i)" + literalToRegex(key) + "=" + quoted.replace(PAUSE_COMMAND, "[pause]")
        }
        return if (disabled) "#$rule" else rule
    }

    /** Wörtlicher Text als regulärer Ausdruck, gut lesbar: nur Sonderzeichen maskieren. */
    internal fun literalToRegex(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in REGEX_META) sb.append('\\')
            sb.append(c)
        }
        return protectEdges(sb.toString())
    }

    /** Ein unmaskiertes `=` außerhalb von Zeichenklassen, `\Q…\E` und Lookarounds als `\=` schreiben. */
    internal fun escapeSeparators(pattern: String): String {
        val sb = StringBuilder()
        var rest = pattern
        while (true) {
            val i = SpeechRules.regexSeparator(rest)
            if (i < 0) { sb.append(rest); break }
            sb.append(rest, 0, i).append("\\=")
            rest = rest.substring(i + 1)
        }
        return sb.toString()
    }

    /** Leerzeichen am Rand würden beim Einlesen abgeschnitten, deshalb als \x20 schreiben. */
    private fun protectEdges(p: String): String {
        var s = p
        var lead = 0
        while (s.startsWith(" ")) { s = s.substring(1); lead++ }
        var trail = 0
        while (s.endsWith(" ")) {
            s = s.dropLast(1)
            // "\ " war ein maskiertes Leerzeichen: den einzelnen Backslash mit entfernen
            val backslashes = s.takeLastWhile { it == '\\' }.length
            if (backslashes % 2 == 1) s = s.dropLast(1)
            trail++
        }
        return "\\x20".repeat(lead) + s + "\\x20".repeat(trail)
    }

    /**
     * Nachsichtiger JSON-Leser für flache Objekte aus Zeichenketten, wie org.json sie bei Librera akzeptiert
     * (einfache oder doppelte Anführungszeichen, überzählige Kommas). Liefert die Paare in Dateireihenfolge
     * oder null, wenn der Text kein solches Objekt ist.
     */
    internal object LenientJson {
        fun parseObject(s: String): List<Pair<String, String>>? {
            var i = skipWs(s, 0)
            if (i >= s.length || s[i] != '{') return null
            i++
            val out = ArrayList<Pair<String, String>>()
            while (true) {
                i = skipWs(s, i)
                if (i >= s.length) return null
                if (s[i] == '}') return out
                if (s[i] == ',') { i++; continue }
                val (key, afterKey) = readValue(s, i) ?: return null
                i = skipWs(s, afterKey)
                if (i >= s.length || (s[i] != ':' && s[i] != '=')) return null
                i = skipWs(s, i + 1)
                val (value, afterValue) = readValue(s, i) ?: return null
                out += key to value
                i = afterValue
            }
        }

        private fun skipWs(s: String, from: Int): Int {
            var i = from
            while (i < s.length && s[i].isWhitespace()) i++
            return i
        }

        /** Zeichenkette in ' oder ", sonst ein schlichter Wert (Zahl, true, null) bis zum nächsten Trennzeichen. */
        private fun readValue(s: String, from: Int): Pair<String, Int>? {
            if (from >= s.length) return null
            val q = s[from]
            if (q != '"' && q != '\'') {
                var i = from
                while (i < s.length && s[i] !in ",:}" && !s[i].isWhitespace()) i++
                return if (i == from) null else s.substring(from, i) to i
            }
            val sb = StringBuilder()
            var i = from + 1
            while (i < s.length) {
                val c = s[i]
                when {
                    c == q -> return sb.toString() to i + 1
                    c == '\\' && i + 1 < s.length -> {
                        val e = s[i + 1]
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i + 2, minOf(i + 6, s.length))
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar()); i += 4
                            }
                            else -> sb.append(e)   // \" \' \\ \/
                        }
                        i += 2
                        continue
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return null
        }
    }
}
