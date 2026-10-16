package app.simplenarrator.speech

import app.simplenarrator.speech.GermanNumbers.One

/**
 * Bereitet Buchtext fuer die Sprachausgabe auf: Zahlen, Daten, Uhrzeiten, Geldbetraege, Einheiten,
 * roemische Ziffern und Abkuerzungen werden ausgeschrieben, Fussnotenzeichen und Satzschmuck entfernt.
 *
 * Bewusst regelbasiert statt mit einem Sprachmodell: Regeln sind nachpruefbar und veraendern nie
 * eine Zahl. Was keine Regel sicher erkennt, bleibt stehen und geht unveraendert an espeak-ng.
 *
 * Angezeigt wird weiter der Originaltext - normalisiert wird nur, was gesprochen wird.
 */
object Normalizer {

    /** [espeak]: Die Stimme liest über espeak-ng (Piper, Kokoro, Kitten); dann werden auch Interjektionen umgeschrieben. */
    fun normalize(text: String, lang: String, espeak: Boolean = true): String {
        if (text.isBlank()) return text
        var t = Speakable.apply(cleanup(text), lang, espeak)
        t = if (lang == "en") english(t) else german(t)
        t = t.replace(WS, " ").replace(SPACE_BEFORE_PUNCT, "$1").trim()
        // Hat eine Regel den Satzendpunkt mitverbraucht ("Baker St.", "3:30 pm."), kommt er zurueck -
        // sonst fehlt die fallende Satzmelodie am Ende.
        val end = text.trimEnd().lastOrNull()
        if (end != null && end in ".!?" && t.isNotEmpty() && t.last() !in ".!?") t += end
        return t
    }

    private val WS = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?])""")

    /** Zahl im deutschen Format: 1.234.567,89 oder 1234,5 oder 42. */
    private const val DE_NUM = """\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:,\d+)?"""

    /** Zahl im englischen Format: 1,234,567.89 oder 1234.5 oder 42. */
    private const val EN_NUM = """\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"""

    // ================================================================ gemeinsam

    private fun cleanup(s: String): String {
        var t = s
        t = t.replace('\u00A0', ' ').replace('\u2009', ' ').replace('\u202F', ' ')
        t = t.replace('\u2212', '-')
        t = t.replace(Regex("""[‐‑‒―]"""), "–")
        t = t.replace(Regex("""\[\d{1,3}]"""), "")                    // Fussnoten [12]
        t = t.replace(Regex("""(?<!m)[¹²³⁴⁵⁶⁷⁸⁹⁰]+"""), "")           // hochgestellte Fussnoten (m² bleibt)
        t = t.replace(Regex("""\*{2,}|_{2,}|~{2,}|#{2,}"""), " ")
        t = t.replace("½", " 1/2").replace("¼", " 1/4").replace("¾", " 3/4")
        return t
    }

    private fun urls(s: String, dot: String, at: String): String {
        var t = s.replace(Regex("""(?:https?://)?(?:www\.)?((?:[\p{L}\d-]+\.)+[\p{L}]{2,})(?:/\S*)?""")) { m ->
            if (!m.value.contains("://") && !m.value.startsWith("www.")) m.value
            else m.groupValues[1].replace(".", " $dot ")
        }
        t = t.replace(Regex("""[\w.+-]+@(?:[\w-]+\.)+\w+""")) { m ->
            m.value.replace("@", " $at ").replace(".", " $dot ")
        }
        return t
    }

    /** Zahl im deutschen Format zerlegen: "1.234,5" -> (1234, "5"). */
    private fun deParse(s: String): Pair<Long, String>? {
        val parts = s.split(',')
        val int = parts[0].replace(".", "").toLongOrNull() ?: return null
        return int to (parts.getOrNull(1) ?: "")
    }

    private fun enParse(s: String): Pair<Long, String>? {
        val parts = s.split('.')
        val int = parts[0].replace(",", "").toLongOrNull() ?: return null
        return int to (parts.getOrNull(1) ?: "")
    }

    private fun Regex.sub(t: String, f: (MatchResult) -> String?): String =
        replace(t) { m -> f(m) ?: m.value }

    private fun keepPrev(prev: String?): String = if (prev.isNullOrEmpty()) "" else "$prev "

    // ================================================================ Deutsch

    private fun german(input: String): String {
        var t = urls(input, "Punkt", "at")
        t = deAbbreviations(t)
        t = deIsoDates(t)
        t = deRanges(t)
        t = deDates(t)
        t = deTimes(t)
        t = deScale(t)
        t = deCurrency(t)
        t = dePercentTemp(t)
        t = deUnits(t)
        t = deFractions(t)
        t = deRoman(t)
        t = deOrdinals(t)
        t = deYears(t)
        t = deSymbolsBefore(t)
        t = deLongDigits(t)
        t = deNumbers(t)
        t = deSymbolsAfter(t)
        return t
    }

    // ---------------------------------------------------------------- Abkuerzungen

    private val deMulti = listOf(
        "z. B." to "zum Beispiel", "d. h." to "das heißt", "u. a." to "unter anderem",
        "u. U." to "unter Umständen", "o. Ä." to "oder Ähnliches", "u. Ä." to "und Ähnliches",
        "s. o." to "siehe oben", "s. u." to "siehe unten", "i. d. R." to "in der Regel",
        "v. a." to "vor allem", "m. E." to "meines Erachtens", "v. Chr." to "vor Christus",
        "n. Chr." to "nach Christus", "u. v. m." to "und vieles mehr", "z. T." to "zum Teil",
        "z. Zt." to "zur Zeit", "i. A." to "im Auftrag", "o. g." to "oben genannt",
        "a. D." to "außer Dienst", "i. R." to "im Ruhestand", "d. J." to "dieses Jahres",
    ).sortedByDescending { it.first.length }.map { (abbr, full) ->
        val parts = abbr.removeSuffix(".").split(". ")
        val pattern = """(?<![\p{L}])""" + parts.joinToString("""\.\s?""") { Regex.escape(it) } + """\."""
        Regex(pattern, RegexOption.IGNORE_CASE) to full
    }

    private enum class When { ALWAYS, BEFORE_DIGIT, BEFORE_CAPITAL }

    private val deSingle: List<Triple<Regex, String, Boolean>> = listOf(
        Triple("usw", "und so weiter", When.ALWAYS), Triple("bzw", "beziehungsweise", When.ALWAYS),
        Triple("ca", "circa", When.ALWAYS), Triple("etc", "et cetera", When.ALWAYS),
        Triple("vgl", "vergleiche", When.ALWAYS), Triple("ggf", "gegebenenfalls", When.ALWAYS),
        Triple("evtl", "eventuell", When.ALWAYS), Triple("inkl", "inklusive", When.ALWAYS),
        Triple("exkl", "exklusive", When.ALWAYS), Triple("zzgl", "zuzüglich", When.ALWAYS),
        Triple("bspw", "beispielsweise", When.ALWAYS), Triple("insb", "insbesondere", When.ALWAYS),
        Triple("sog", "sogenannte", When.ALWAYS), Triple("bzgl", "bezüglich", When.ALWAYS),
        Triple("Nr", "Nummer", When.ALWAYS), Triple("Dr", "Doktor", When.ALWAYS),
        Triple("Prof", "Professor", When.ALWAYS), Triple("Str", "Straße", When.ALWAYS),
        Triple("Kap", "Kapitel", When.ALWAYS), Triple("Abb", "Abbildung", When.ALWAYS),
        Triple("Tab", "Tabelle", When.ALWAYS), Triple("Bd", "Band", When.ALWAYS),
        Triple("Jhd", "Jahrhundert", When.ALWAYS), Triple("Jh", "Jahrhundert", When.ALWAYS),
        Triple("Hrsg", "Herausgeber", When.ALWAYS), Triple("geb", "geboren", When.ALWAYS),
        Triple("gest", "gestorben", When.ALWAYS), Triple("Tel", "Telefon", When.ALWAYS),
        Triple("Abs", "Absatz", When.ALWAYS), Triple("ff", "folgende", When.ALWAYS),
        Triple("Mo", "Montag", When.BEFORE_DIGIT), Triple("Di", "Dienstag", When.BEFORE_DIGIT),
        Triple("Mi", "Mittwoch", When.BEFORE_DIGIT), Triple("Do", "Donnerstag", When.BEFORE_DIGIT),
        Triple("Fr", "Freitag", When.BEFORE_DIGIT), Triple("Sa", "Samstag", When.BEFORE_DIGIT),
        Triple("So", "Sonntag", When.BEFORE_DIGIT),
        Triple("S", "Seite", When.BEFORE_DIGIT), Triple("Art", "Artikel", When.BEFORE_DIGIT),
        Triple("St", "Sankt", When.BEFORE_CAPITAL), Triple("Hr", "Herr", When.BEFORE_CAPITAL),
        Triple("Fr", "Frau", When.BEFORE_CAPITAL),
    ).map { (abbr, full, cond) ->
        val first = abbr[0]
        // Erster Buchstabe in beiden Schreibungen, damit "Vgl." am Satzanfang auch erkannt wird
        val head = if (first.isLowerCase()) "[${first}${first.uppercaseChar()}]" else "$first"
        val look = when (cond) {
            When.ALWAYS -> ""
            When.BEFORE_DIGIT -> """(?=\s?\d)"""
            When.BEFORE_CAPITAL -> """(?=\s?\p{Lu})"""
        }
        Triple(Regex("""(?<![\p{L}\d])$head${Regex.escape(abbr.drop(1))}\.$look"""), full, first.isLowerCase())
    }

    private fun deAbbreviations(input: String): String {
        // Strassennamen: "Hauptstr.", "Bahnhofstr." - das Kuerzel haengt direkt am Wort
        var t = Regex("""(?<=\p{L})str\.""").replace(input, "straße")
        for ((rx, full) in deMulti) {
            t = rx.sub(t) { m -> withCase(m.value, full) + endDot(m, t) }
        }
        for ((rx, full, lowerCanonical) in deSingle) {
            t = rx.sub(t) { m ->
                (if (lowerCanonical) withCase(m.value, full) else full) + endDot(m, t)
            }
        }
        return t
    }

    /** Steht die Abkuerzung am Satzende, bleibt der Punkt fuer die Satzmelodie erhalten. */
    private fun endDot(m: MatchResult, t: String): String =
        if (t.substring(m.range.last + 1).isBlank()) "." else ""

    private fun withCase(original: String, full: String): String =
        if (original.first().isUpperCase()) full.replaceFirstChar { it.uppercaseChar() } else full

    // ---------------------------------------------------------------- Daten

    private val dativeTriggers = setOf(
        "am", "vom", "zum", "beim", "im", "seit", "bis", "ab", "zur", "dem", "den", "des",
        "einem", "seinem", "ihrem", "meinem", "deinem", "unserem", "eurem", "diesem", "jedem", "jenem",
        "vor", "nach", "mit", "von", "zu", "bei", "aus", "an", "unter", "gegenüber",
    )
    private val nominativeTriggers = setOf(
        "der", "die", "das", "dieser", "diese", "dieses", "jeder", "jede", "jedes",
        "ihr", "ihre", "sein", "seine", "mein", "meine", "unser", "unsere",
    )

    /** Endung einer Ordnungszahl nach dem vorangehenden Wort. */
    private fun ordEnding(prev: String?, default: String): String {
        val p = prev?.lowercase() ?: return default
        return when {
            p in dativeTriggers -> "en"
            p in nominativeTriggers -> "e"
            else -> default
        }
    }

    private const val DE_MONTHS =
        """Januar|Jänner|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember|""" +
            """Jan\.|Febr?\.|Mär\.|Apr\.|Jun\.|Jul\.|Aug\.|Sept?\.|Okt\.|Nov\.|Dez\."""

    private fun deMonth(token: String): String = when (token.lowercase().take(3)) {
        "jan", "jän" -> "Januar"; "feb" -> "Februar"; "mär" -> "März"; "apr" -> "April"
        "mai" -> "Mai"; "jun" -> "Juni"; "jul" -> "Juli"; "aug" -> "August"
        "sep" -> "September"; "okt" -> "Oktober"; "nov" -> "November"; "dez" -> "Dezember"
        else -> token
    }

    private fun twoDigitYear(y: String): Int {
        val n = y.toInt()
        return if (y.length == 2) (if (n <= 30) 2000 + n else 1900 + n) else n
    }

    private fun deIsoDates(t: String): String =
        Regex("""(?:\b(\p{L}+)\s+)?\b(\d{4})-(\d{2})-(\d{2})\b""").sub(t) { m ->
            val (prev, y, mo, d) = m.destructured
            val month = mo.toInt()
            val day = d.toInt()
            if (month !in 1..12 || day !in 1..31) return@sub null
            keepPrev(prev) + GermanNumbers.ordinal(day.toLong(), ordEnding(prev, "er")) + " " +
                GermanNumbers.months[month] + " " + GermanNumbers.year(y.toInt())
        }

    private fun deDates(input: String): String {
        var t = input
        // "3.–5. Mai", "3. bis 5. Mai"
        t = Regex("""(?:\b(\p{L}+)\s+)?\b(\d{1,2})\.\s?(?:–|-|bis)\s?(\d{1,2})\.\s?($DE_MONTHS)""").sub(t) { m ->
            val (prev, a, b, mon) = m.destructured
            val end = ordEnding(prev, "er")
            keepPrev(prev) + GermanNumbers.ordinal(a.toLong(), end) + " bis " +
                GermanNumbers.ordinal(b.toLong(), end) + " " + deMonth(mon)
        }
        // "12.10.2024", "3.5." 
        t = Regex("""(?:\b(\p{L}+)\s+)?(?<![\d.])(\d{1,2})\.\s?(\d{1,2})\.(?:\s?(\d{4}|\d{2})(?![\d.,]))?""").sub(t) { m ->
            val (prev, d, mo, y) = m.destructured
            val day = d.toInt()
            val month = mo.toInt()
            if (day !in 1..31 || month !in 1..12) return@sub null
            keepPrev(prev) + GermanNumbers.ordinal(day.toLong(), ordEnding(prev, "er")) + " " +
                GermanNumbers.months[month] + (if (y.isNotEmpty()) " " + GermanNumbers.year(twoDigitYear(y)) else "")
        }
        // "3. Mai", "am 24. Dez."
        t = Regex("""(?:\b(\p{L}+)\s+)?\b(\d{1,2})\.\s?($DE_MONTHS)""").sub(t) { m ->
            val (prev, d, mon) = m.destructured
            if (d.toInt() !in 1..31) return@sub null
            keepPrev(prev) + GermanNumbers.ordinal(d.toLong(), ordEnding(prev, "er")) + " " + deMonth(mon)
        }
        return t
    }

    private fun deRanges(t: String): String {
        var s = Regex("""\b(1[1-9]\d{2}|20\d{2})/(\d{2})(?!\d)""").replace(t) { m ->
            m.groupValues[1] + " bis " + m.groupValues[2]
        }
        s = Regex("""(?<=\d)\s?[–-]\s?(?=\d)""").replace(s, " bis ")
        return s
    }

    // ---------------------------------------------------------------- Uhrzeit

    private fun hourWord(h: Int): String = if (h == 1) "ein" else GermanNumbers.cardinal(h.toLong())

    private fun deTimes(input: String): String {
        var t = Regex("""(?<![\d.,:])(\d{1,2})[:.](\d{2})\s?Uhr\b""").sub(input) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 24 || min > 59) return@sub null
            hourWord(h) + " Uhr" + (if (min > 0) " " + GermanNumbers.cardinal(min.toLong()) else "")
        }
        t = Regex("""(?<![\d.,:])(\d{1,2})\s?Uhr\b""").sub(t) { m ->
            val h = m.groupValues[1].toInt()
            if (h > 24) null else hourWord(h) + " Uhr"
        }
        t = Regex("""(?<![\d.,:])([01]?\d|2[0-4]):([0-5]\d)(?![\d:])""").sub(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            hourWord(h) + " Uhr" + (if (min > 0) " " + GermanNumbers.cardinal(min.toLong()) else "")
        }
        return t
    }

    // ---------------------------------------------------------------- Zahl + Wort

    /** Zahl als Woerter; [one] bestimmt die Form, wenn der Wert genau 1 ist. */
    private fun deWords(num: String, one: One = One.STANDALONE): String? {
        val (int, dec) = deParse(num) ?: return null
        return if (dec.isNotEmpty()) GermanNumbers.decimal(int, dec) else GermanNumbers.cardinal(int, one)
    }

    private fun isExactlyOne(num: String): Boolean = deParse(num)?.let { it.first == 1L && it.second.isEmpty() } ?: false

    private fun deScale(t: String): String =
        Regex("""(?<![\d.,])($DE_NUM)\s?(Mio\.?|Mrd\.?|Millionen|Million|Milliarden|Milliarde|Billionen|Billion|Tsd\.)(?!\p{L})""").sub(t) { m ->
            val num = m.groupValues[1]
            val unit = m.groupValues[2]
            val one = isExactlyOne(num)
            when {
                unit.startsWith("Mio") || unit.startsWith("Million") ->
                    if (one) "eine Million" else "${deWords(num, One.EINE)} Millionen"
                unit.startsWith("Mrd") || unit.startsWith("Milliard") ->
                    if (one) "eine Milliarde" else "${deWords(num, One.EINE)} Milliarden"
                unit.startsWith("Billion") ->
                    if (one) "eine Billion" else "${deWords(num, One.EINE)} Billionen"
                else -> if (one) "tausend" else "${deWords(num)} Tausend"
            }
        }

    private fun deCurrency(input: String): String {
        // Symbol vor der Zahl ("€ 12,50", "$5") nach hinten holen
        var t = Regex("""([€£]|[$])\s?($DE_NUM)""").replace(input) { m -> "${m.groupValues[2]} ${m.groupValues[1]}" }
        t = Regex("""(?<![\d.,])(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}|–|-{1,2}))?\s?(€|EUR|Euro|[$]|USD|Dollar|£|GBP|Pfund|CHF|Franken|Cent|ct\.?)(?!\p{L})""").sub(t) { m ->
            val main = m.groupValues[1].replace(".", "").toLongOrNull() ?: return@sub null
            val centsRaw = m.groupValues[2]
            val cents = when {
                centsRaw.isEmpty() || !centsRaw[0].isDigit() -> 0L
                centsRaw.length == 1 -> centsRaw.toLong() * 10
                else -> centsRaw.toLong()
            }
            val unit = m.groupValues[3]
            if (unit.startsWith("Cent") || unit.startsWith("ct")) {
                return@sub GermanNumbers.cardinal(main, One.EIN) + " Cent"
            }
            val name = when (unit) {
                "€", "EUR", "Euro" -> "Euro"
                "$", "USD", "Dollar" -> "Dollar"
                "£", "GBP", "Pfund" -> "Pfund"
                else -> "Franken"
            }
            if (main == 0L && cents > 0) GermanNumbers.cardinal(cents, One.EIN) + " Cent"
            else GermanNumbers.cardinal(main, One.EIN) + " " + name +
                (if (cents > 0) " " + GermanNumbers.cardinal(cents) else "")
        }
        return t
    }

    private fun dePercentTemp(input: String): String {
        var t = Regex("""(?<![\d.,])(-?)($DE_NUM)\s?(%|‰)""").sub(input) { m ->
            val sign = if (m.groupValues[1] == "-") "minus " else ""
            val w = deWords(m.groupValues[2], One.EIN) ?: return@sub null
            sign + w + if (m.groupValues[3] == "%") " Prozent" else " Promille"
        }
        t = Regex("""(?<![\d.,])(-?)($DE_NUM)\s?°\s?([CF])(?!\p{L})""").sub(t) { m ->
            val sign = if (m.groupValues[1] == "-") "minus " else ""
            val w = deWords(m.groupValues[2], One.EIN) ?: return@sub null
            sign + w + " Grad " + if (m.groupValues[3] == "C") "Celsius" else "Fahrenheit"
        }
        t = Regex("""(?<![\d.,])(-?)($DE_NUM)\s?°""").sub(t) { m ->
            val sign = if (m.groupValues[1] == "-") "minus " else ""
            val w = deWords(m.groupValues[2], One.EIN) ?: return@sub null
            "$sign$w Grad"
        }
        return t
    }

    /** Einheit -> (Einzahl, Mehrzahl, weiblich). */
    private val deUnitTable = linkedMapOf(
        "km/h" to Triple("Kilometer pro Stunde", "Kilometer pro Stunde", false),
        "m/s" to Triple("Meter pro Sekunde", "Meter pro Sekunde", false),
        "km²" to Triple("Quadratkilometer", "Quadratkilometer", false),
        "cm²" to Triple("Quadratzentimeter", "Quadratzentimeter", false),
        "m²" to Triple("Quadratmeter", "Quadratmeter", false),
        "cm³" to Triple("Kubikzentimeter", "Kubikzentimeter", false),
        "m³" to Triple("Kubikmeter", "Kubikmeter", false),
        "kWh" to Triple("Kilowattstunde", "Kilowattstunden", true),
        "MWh" to Triple("Megawattstunde", "Megawattstunden", true),
        "km" to Triple("Kilometer", "Kilometer", false),
        "cm" to Triple("Zentimeter", "Zentimeter", false),
        "mm" to Triple("Millimeter", "Millimeter", false),
        "kg" to Triple("Kilogramm", "Kilogramm", false),
        "mg" to Triple("Milligramm", "Milligramm", false),
        "ml" to Triple("Milliliter", "Milliliter", false),
        "kW" to Triple("Kilowatt", "Kilowatt", false),
        "MW" to Triple("Megawatt", "Megawatt", false),
        "GW" to Triple("Gigawatt", "Gigawatt", false),
        "GB" to Triple("Gigabyte", "Gigabyte", false),
        "MB" to Triple("Megabyte", "Megabyte", false),
        "TB" to Triple("Terabyte", "Terabyte", false),
        "kB" to Triple("Kilobyte", "Kilobyte", false),
        "GHz" to Triple("Gigahertz", "Gigahertz", false),
        "MHz" to Triple("Megahertz", "Megahertz", false),
        "kHz" to Triple("Kilohertz", "Kilohertz", false),
        "Hz" to Triple("Hertz", "Hertz", false),
        "Std." to Triple("Stunde", "Stunden", true),
        "Std" to Triple("Stunde", "Stunden", true),
        "Min." to Triple("Minute", "Minuten", true),
        "min" to Triple("Minute", "Minuten", true),
        "Sek." to Triple("Sekunde", "Sekunden", true),
        "sek" to Triple("Sekunde", "Sekunden", true),
        "m" to Triple("Meter", "Meter", false),
        "g" to Triple("Gramm", "Gramm", false),
        "l" to Triple("Liter", "Liter", false),
        "t" to Triple("Tonne", "Tonnen", true),
        "h" to Triple("Stunde", "Stunden", true),
        "s" to Triple("Sekunde", "Sekunden", true),
    )

    private val deUnitRegex = Regex(
        """(?<![\d.,])(-?)($DE_NUM)\s?(""" + deUnitTable.keys.joinToString("|") { Regex.escape(it) } + """)(?![\p{L}\d²³])"""
    )

    private fun deUnits(t: String): String = deUnitRegex.sub(t) { m ->
        val sign = if (m.groupValues[1] == "-") "minus " else ""
        val num = m.groupValues[2]
        val (sing, plur, fem) = deUnitTable[m.groupValues[3]] ?: return@sub null
        val one = isExactlyOne(num)
        val w = deWords(num, if (fem) One.EINE else One.EIN) ?: return@sub null
        "$sign$w ${if (one) sing else plur}"
    }

    private fun deFractions(t: String): String =
        Regex("""(?<![\d/.,])(\d{1,2})/(\d{1,3})(?![\d/])""").sub(t) { m ->
            val num = m.groupValues[1].toLong()
            val den = m.groupValues[2].toLong()
            if (num < 1 || den !in 2..100) null else GermanNumbers.fraction(num, den)
        }

    // ---------------------------------------------------------------- Roemische Zahlen

    private const val DE_ROMAN_KEYWORDS =
        "Kapitel|Band|Teil|Akt|Szene|Buch|Abschnitt|Folge|Staffel|Lektion|Psalm|Gesang|Strophe|Artikel|Anhang|Tafel"

    private val feminineRulers = setOf("elisabeth", "elizabeth", "katharina", "maria", "victoria", "viktoria", "anne", "anna")

    private fun deRoman(input: String): String {
        var t = Regex("""\b($DE_ROMAN_KEYWORDS)\s+([IVXLCDM]{1,8})(?![\p{L}])""").sub(input) { m ->
            val v = Roman.parse(m.groupValues[2])?.takeIf { it in 1..300 } ?: return@sub null
            m.groupValues[1] + " " + GermanNumbers.cardinal(v.toLong())
        }
        t = Regex("""(?:\b(\p{L}+)\s+)?\b([IVXLCDM]{1,6})\.\s?(Jahrhundert|Jahrtausend)""").sub(t) { m ->
            val (prev, r, word) = m.destructured
            val v = Roman.parse(r)?.takeIf { it in 1..30 } ?: return@sub null
            keepPrev(prev) + GermanNumbers.ordinal(v.toLong(), ordEnding(prev, "e")) + " " + word
        }
        // Herrschernamen: "Ludwig XIV." -> "Ludwig der Vierzehnte", "unter Ludwig XIV." -> "unter Ludwig dem Vierzehnten"
        t = Regex("""(?:\b(\p{L}+)\s+)?\b(\p{Lu}\p{Ll}{2,})\s+([IVX]{1,6})\.""").sub(t) { m ->
            val (prev, name, r) = m.destructured
            val v = Roman.parse(r)?.takeIf { it in 1..30 } ?: return@sub null
            val fem = name.lowercase() in feminineRulers || name.endsWith("a")
            val dative = prev.lowercase() in dativeTriggers
            val article = when {
                dative && fem -> "der"; dative -> "dem"; fem -> "die"; else -> "der"
            }
            val ord = GermanNumbers.ordinal(v.toLong(), if (dative) "en" else "e").replaceFirstChar { it.uppercaseChar() }
            keepPrev(prev) + "$name $article $ord" + endDot(m, t)
        }
        return t
    }

    // ---------------------------------------------------------------- Ordnungszahlen und Jahre

    private const val DE_ORD_ARTICLES =
        "der|die|das|den|dem|des|am|vom|zum|beim|im|ins|zur|ihr|ihre|ihrem|ihren|ihrer|sein|seine|seinem|seinen|seiner|" +
            "mein|meine|meinem|meinen|unser|unsere|unserem|jeder|jede|jedem|jeden|dieser|diese|diesem|diesen"

    private const val DE_ORD_NOUNS =
        "Jahrhundert|Jahrtausend|Jahrestag|Geburtstag|Hochzeitstag|Platz|Stock|Stockwerk|Etage|Klasse|Mal|" +
            "Auflage|Liga|Runde|Spieltag|Kapitel|Band|Lauf|Woche|Monat|Quartal|Semester|Weltkrieg|Reich|" +
            "Generation|Ausgabe|Sitzung|Satz|Strophe|Vers|Akt|Szene|Teil|Tag|Sinfonie|Symphonie|Buch"

    private fun deOrdinals(input: String): String {
        var t = Regex("""\b($DE_ORD_ARTICLES)\s+(\d{1,3})\.(?=\s)""", RegexOption.IGNORE_CASE).sub(input) { m ->
            val prev = m.groupValues[1]
            "$prev " + GermanNumbers.ordinal(m.groupValues[2].toLong(), ordEnding(prev, "e"))
        }
        t = Regex("""(?:\b(\p{L}+)\s+)?(?<![\d.,])(\d{1,3})\.\s+(?=(?:$DE_ORD_NOUNS)\b)""").sub(t) { m ->
            val prev = m.groupValues[1]
            keepPrev(prev) + GermanNumbers.ordinal(m.groupValues[2].toLong(), ordEnding(prev, "e")) + " "
        }
        return t
    }

    private val yearTriggers = setOf(
        "im", "jahr", "jahre", "jahres", "jahrgang", "seit", "bis", "ab", "von", "um", "vor", "nach",
        "anno", "sommer", "winter", "frühjahr", "frühling", "herbst", "ende", "anfang", "mitte",
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september",
        "oktober", "november", "dezember", "christus", "geboren", "gestorben", "erst", "schon",
    )

    private fun deYears(input: String): String {
        var t = Regex("""(?<![\d.,])(\d{4})er(?=\p{L}|\b)""").sub(input) { m ->
            val n = m.groupValues[1].toInt()
            GermanNumbers.year(n) + "er"
        }
        t = Regex("""(?<![\d.,/])(1[1-9]\d{2})(?![\d]|[.,]\d)""").sub(t) { m ->
            val before = t.substring(0, m.range.first).trimEnd()
            val prevWord = before.takeLastWhile { it.isLetter() }.lowercase()
            val after = t.substring(m.range.last + 1)
            val followedByNoun = Regex("""^\s+\p{Lu}\p{Ll}""").containsMatchIn(after)
            if (prevWord in yearTriggers || !followedByNoun || before.endsWith("(")) GermanNumbers.year(m.value.toInt())
            else null
        }
        return t
    }

    // ---------------------------------------------------------------- reine Zahlen und Zeichen

    private fun deSymbolsBefore(input: String): String {
        var t = input
        t = t.replace(Regex("""§§\s?"""), "Paragraphen ")
        t = t.replace(Regex("""§\s?"""), "Paragraph ")
        t = t.replace(Regex("""\s&\s"""), " und ")
        t = t.replace(Regex("""(?<=\d)\s?\+\s?(?=\d)"""), " plus ")
        t = t.replace(Regex("""(?<=\d)\s?[×x]\s?(?=\d)"""), " mal ")
        t = t.replace("±", " plus minus ")
        t = t.replace(Regex("""~\s?(?=\d)"""), "circa ")
        t = t.replace(Regex("""#\s?(?=\d)"""), "Nummer ")
        t = t.replace(Regex("""(?<=^|[\s(])-(?=\d)"""), "minus ")
        return t
    }

    private fun deLongDigits(t: String): String =
        Regex("""(?<![\d.,])(0\d{3,}|\d{13,})(?![\d,])""").replace(t) { m -> GermanNumbers.digits(m.value) }

    private val femEndings = listOf("ung", "heit", "keit", "schaft", "ion", "tät", "ik", "ei", "ie", "enz", "anz", "ur", "erin", "e")
    private val notFeminine = setOf(
        "name", "junge", "käse", "auge", "ende", "gedanke", "glaube", "buchstabe", "friede", "wille", "same",
        "funke", "kunde", "löwe", "affe", "hase", "rabe", "bote", "erbe", "gatte", "experte", "kollege", "neffe",
        "franzose", "russe", "türke", "grieche", "schwede", "zeuge", "knabe", "riese", "sklave", "gebäude",
        "gemälde", "getreide", "gebirge", "gelände", "interesse", "finale", "gemüse", "ei", "knie", "flur",
        "schwur", "brei", "schrei", "papagei", "abitur", "charme",
    )

    /** "1 Katze" -> "eine Katze", "1 Hund" -> "ein Hund": Genus aus Endung, Adjektiv oder Ausnahmeliste. */
    private fun oneBefore(adjective: String, noun: String): One {
        if (adjective.isNotEmpty()) {
            return if (adjective.endsWith("e")) One.EINE else One.EIN
        }
        val n = noun.lowercase()
        if (n in notFeminine) return One.EIN
        return if (femEndings.any { n.endsWith(it) }) One.EINE else One.EIN
    }

    private fun deNumbers(t: String): String =
        Regex("""(?<![\p{L}\d.,])(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d+))?(?:,[-–]{1,2})?(?![\p{L}\d])""").sub(t) { m ->
            val int = m.groupValues[1].replace(".", "").toLongOrNull() ?: return@sub null
            val dec = m.groupValues[2]
            if (dec.isNotEmpty()) return@sub GermanNumbers.decimal(int, dec)
            if (int == 1L) {
                val after = t.substring(m.range.last + 1)
                val nm = Regex("""^\s+(?:(\p{Ll}+)\s+)?(\p{Lu}\p{Ll}+)""").find(after)
                if (nm != null) return@sub GermanNumbers.cardinal(1, oneBefore(nm.groupValues[1], nm.groupValues[2]))
            }
            GermanNumbers.cardinal(int)
        }

    private fun deSymbolsAfter(input: String): String {
        var t = input
        t = t.replace("€", " Euro").replace("$", " Dollar").replace("£", " Pfund")
        t = t.replace("%", " Prozent").replace("‰", " Promille").replace("°", " Grad")
        t = t.replace(Regex("""(?<![\p{L}])Mio\.(?!\p{L})"""), "Millionen")
        t = t.replace(Regex("""(?<![\p{L}])Mrd\.(?!\p{L})"""), "Milliarden")
        t = t.replace(Regex("""(?<=\p{L})/(?=\p{L})"""), " ")
        t = t.replace(Regex("""\s[–—]\s"""), ", ")
        return t
    }

    // ================================================================ Englisch

    private fun english(input: String): String {
        var t = urls(input, "dot", "at")
        t = enAbbreviations(t)
        t = Regex("""(?<=\d)\s?[–-]\s?(?=\d)""").replace(t, " to ")
        t = enDates(t)
        t = enTimes(t)
        t = enCurrency(t)
        t = enPercentTemp(t)
        t = enUnits(t)
        t = enFractions(t)
        t = enRoman(t)
        t = Regex("""(?<![\d.,])(\d+)(st|nd|rd|th)\b""").replace(t) { m -> EnglishNumbers.ordinal(m.groupValues[1].toLong()) }
        t = enYears(t)
        t = enSymbolsBefore(t)
        t = Regex("""(?<![\d.,])(0\d{3,}|\d{13,})(?![\d,])""").replace(t) { m -> EnglishNumbers.digits(m.value) }
        t = enNumbers(t)
        t = enSymbolsAfter(t)
        return t
    }

    private val enAbbr: List<Triple<Regex, String, When>> = listOf(
        Triple("Mr", "Mister", When.ALWAYS), Triple("Mrs", "Missus", When.ALWAYS), Triple("Ms", "Miz", When.ALWAYS),
        Triple("Dr", "Doctor", When.ALWAYS), Triple("Prof", "Professor", When.ALWAYS),
        Triple("Jr", "Junior", When.ALWAYS), Triple("Sr", "Senior", When.ALWAYS),
        Triple("St", "Saint", When.BEFORE_CAPITAL), Triple("Mt", "Mount", When.ALWAYS),
        Triple("Ave", "Avenue", When.ALWAYS), Triple("Blvd", "Boulevard", When.ALWAYS),
        Triple("vs", "versus", When.ALWAYS), Triple("etc", "et cetera", When.ALWAYS),
        Triple("approx", "approximately", When.ALWAYS), Triple("No", "number", When.BEFORE_DIGIT),
        Triple("Nos", "numbers", When.BEFORE_DIGIT), Triple("Vol", "volume", When.ALWAYS),
        Triple("Ch", "chapter", When.BEFORE_DIGIT), Triple("p", "page", When.BEFORE_DIGIT),
        Triple("pp", "pages", When.BEFORE_DIGIT), Triple("Fig", "figure", When.ALWAYS),
        Triple("Gen", "General", When.BEFORE_CAPITAL), Triple("Col", "Colonel", When.BEFORE_CAPITAL),
        Triple("Capt", "Captain", When.BEFORE_CAPITAL), Triple("Lt", "Lieutenant", When.BEFORE_CAPITAL),
        Triple("Sgt", "Sergeant", When.BEFORE_CAPITAL), Triple("Rev", "Reverend", When.BEFORE_CAPITAL),
        Triple("Gov", "Governor", When.BEFORE_CAPITAL),
    ).map { (abbr, full, cond) ->
        val look = when (cond) {
            When.ALWAYS -> ""
            When.BEFORE_DIGIT -> """(?=\s?\d)"""
            When.BEFORE_CAPITAL -> """(?=\s?\p{Lu})"""
        }
        Triple(Regex("""(?<![\p{L}\d])${Regex.escape(abbr)}\.$look"""), full, cond)
    }

    private fun enAbbreviations(input: String): String {
        var t = input
        t = Regex("""(?<![\p{L}])e\.\s?g\.""", RegexOption.IGNORE_CASE).replace(t, "for example")
        t = Regex("""(?<![\p{L}])i\.\s?e\.""", RegexOption.IGNORE_CASE).replace(t, "that is")
        t = Regex("""\bSt\.(?!\s?\p{Lu})""").replace(t, "Street")
        for ((rx, full) in enAbbr) t = rx.sub(t) { m -> full + endDot(m, t) }
        // U.S., U.K.: Buchstaben einzeln
        t = Regex("""\b((?:\p{Lu}\.){2,})""").replace(t) { m -> m.value.replace(".", " ").trim() }
        return t
    }

    private const val EN_MONTHS =
        """January|February|March|April|May|June|July|August|September|October|November|December|""" +
            """Jan\.|Feb\.|Mar\.|Apr\.|Jun\.|Jul\.|Aug\.|Sept?\.|Oct\.|Nov\.|Dec\."""

    private fun enMonth(token: String): String = when (token.lowercase().take(3)) {
        "jan" -> "January"; "feb" -> "February"; "mar" -> "March"; "apr" -> "April"
        "may" -> "May"; "jun" -> "June"; "jul" -> "July"; "aug" -> "August"
        "sep" -> "September"; "oct" -> "October"; "nov" -> "November"; "dec" -> "December"
        else -> token
    }

    private fun enDates(input: String): String {
        var t = Regex("""\b($EN_MONTHS)\s+(\d{1,2})(?:st|nd|rd|th)?\b(?!:)""").sub(input) { m ->
            val d = m.groupValues[2].toInt()
            if (d !in 1..31) null else enMonth(m.groupValues[1]) + " " + EnglishNumbers.ordinal(d.toLong())
        }
        t = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+($EN_MONTHS)""").sub(t) { m ->
            val d = m.groupValues[1].toInt()
            if (d !in 1..31) null else "the " + EnglishNumbers.ordinal(d.toLong()) + " of " + enMonth(m.groupValues[2])
        }
        return t
    }

    private fun enClock(h: Int, min: Int, ampm: String): String {
        val hour = EnglishNumbers.cardinal(h.toLong())
        val minutes = when {
            min == 0 && ampm.isEmpty() -> " o'clock"
            min == 0 -> ""
            min < 10 -> " oh " + EnglishNumbers.cardinal(min.toLong())
            else -> " " + EnglishNumbers.cardinal(min.toLong())
        }
        val suffix = when (ampm.lowercase()) { "a" -> " a m"; "p" -> " p m"; else -> "" }
        return hour + minutes + suffix
    }

    private fun enTimes(input: String): String {
        var t = Regex("""(?<![\d.,:])(\d{1,2}):([0-5]\d)\s?([aApP])\.?\s?[mM]\.?""").sub(input) { m ->
            enClock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
        }
        t = Regex("""(?<![\d.,:])(\d{1,2})\s?([aApP])\.?\s?[mM]\.?(?!\p{L})""").sub(t) { m ->
            enClock(m.groupValues[1].toInt(), 0, m.groupValues[2])
        }
        t = Regex("""(?<![\d.,:])([01]?\d|2[0-4]):([0-5]\d)(?![\d:])""").sub(t) { m ->
            enClock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), "")
        }
        return t
    }

    private fun enWords(num: String): String? {
        val (int, dec) = enParse(num) ?: return null
        return EnglishNumbers.decimal(int, dec)
    }

    private fun enIsOne(num: String): Boolean = enParse(num)?.let { it.first == 1L && it.second.isEmpty() } ?: false

    private fun enCurrency(input: String): String {
        val names = mapOf("$" to ("dollar" to "cent"), "€" to ("euro" to "cent"), "£" to ("pound" to "penny"))
        var t = Regex("""([$€£])\s?($EN_NUM)(?:\s?(thousand|million|billion|trillion|bn|k)\b)?""").sub(input) { m ->
            val (cur, sub) = names[m.groupValues[1]] ?: return@sub null
            val num = m.groupValues[2]
            val scale = when (m.groupValues[3]) { "k" -> "thousand"; "bn" -> "billion"; else -> m.groupValues[3] }
            if (scale.isNotEmpty()) return@sub "${enWords(num)} $scale ${cur}s"
            val (main, decRaw) = enParse(num) ?: return@sub null
            val cents = when (decRaw.length) { 0 -> 0L; 1 -> decRaw.toLong() * 10; else -> decRaw.take(2).toLong() }
            val mainPart = EnglishNumbers.cardinal(main) + " " + (if (main == 1L) cur else "${cur}s")
            val subPart = when {
                cents == 0L -> ""
                sub == "penny" -> EnglishNumbers.cardinal(cents) + (if (cents == 1L) " penny" else " pence")
                else -> EnglishNumbers.cardinal(cents) + (if (cents == 1L) " cent" else " cents")
            }
            when {
                main == 0L && cents > 0 -> subPart
                cents > 0 -> "$mainPart and $subPart"
                else -> mainPart
            }
        }
        t = Regex("""(?<![\d.,])($EN_NUM)\s?(USD|EUR|GBP)\b""").sub(t) { m ->
            val w = enWords(m.groupValues[1]) ?: return@sub null
            val name = when (m.groupValues[2]) { "USD" -> "dollar"; "EUR" -> "euro"; else -> "pound" }
            "$w ${if (enIsOne(m.groupValues[1])) name else name + "s"}"
        }
        return t
    }

    private fun enPercentTemp(input: String): String {
        var t = Regex("""(?<![\d.,])(-?)($EN_NUM)\s?%""").sub(input) { m ->
            (if (m.groupValues[1] == "-") "minus " else "") + enWords(m.groupValues[2]) + " percent"
        }
        t = Regex("""(?<![\d.,])(-?)($EN_NUM)\s?°\s?([CF])(?!\p{L})""").sub(t) { m ->
            val one = enIsOne(m.groupValues[2])
            (if (m.groupValues[1] == "-") "minus " else "") + enWords(m.groupValues[2]) +
                (if (one) " degree " else " degrees ") + if (m.groupValues[3] == "C") "Celsius" else "Fahrenheit"
        }
        return t
    }

    private val enUnitTable = linkedMapOf(
        "km/h" to ("kilometer per hour" to "kilometers per hour"), "mph" to ("mile per hour" to "miles per hour"),
        "km" to ("kilometer" to "kilometers"), "cm" to ("centimeter" to "centimeters"),
        "mm" to ("millimeter" to "millimeters"), "kg" to ("kilogram" to "kilograms"),
        "mg" to ("milligram" to "milligrams"), "ml" to ("milliliter" to "milliliters"),
        "lbs" to ("pound" to "pounds"), "lb" to ("pound" to "pounds"), "oz" to ("ounce" to "ounces"),
        "ft" to ("foot" to "feet"), "kWh" to ("kilowatt hour" to "kilowatt hours"), "kW" to ("kilowatt" to "kilowatts"),
        "GB" to ("gigabyte" to "gigabytes"), "MB" to ("megabyte" to "megabytes"), "TB" to ("terabyte" to "terabytes"),
        "GHz" to ("gigahertz" to "gigahertz"), "MHz" to ("megahertz" to "megahertz"), "Hz" to ("hertz" to "hertz"),
        "min" to ("minute" to "minutes"), "m" to ("meter" to "meters"), "g" to ("gram" to "grams"),
    )

    private val enUnitRegex = Regex(
        """(?<![\d.,])(-?)($EN_NUM)\s?(""" + enUnitTable.keys.joinToString("|") { Regex.escape(it) } + """)(?![\p{L}\d])"""
    )

    private fun enUnits(t: String): String = enUnitRegex.sub(t) { m ->
        val (sing, plur) = enUnitTable[m.groupValues[3]] ?: return@sub null
        (if (m.groupValues[1] == "-") "minus " else "") + enWords(m.groupValues[2]) + " " +
            if (enIsOne(m.groupValues[2])) sing else plur
    }

    private fun enFractions(t: String): String =
        Regex("""(?<![\d/.,])(\d{1,2})/(\d{1,3})(?![\d/])""").sub(t) { m ->
            val num = m.groupValues[1].toLong()
            val den = m.groupValues[2].toLong()
            if (num < 1 || den !in 2..100) null else EnglishNumbers.fraction(num, den)
        }

    private const val EN_ROMAN_KEYWORDS =
        "Chapter|Part|Book|Volume|Act|Scene|Section|Appendix|Psalm|Canto|Article|Episode|Season|Phase|Level|Stage|War"

    private val enRegnalStop = setOf("The", "Then", "When", "What", "And", "But", "This", "That", "There", "Here", "Now")

    private fun enRoman(input: String): String {
        var t = Regex("""\b($EN_ROMAN_KEYWORDS)\s+([IVXLCDM]{1,8})(?![\p{L}'’])""").sub(input) { m ->
            val v = Roman.parse(m.groupValues[2])?.takeIf { it in 1..300 } ?: return@sub null
            m.groupValues[1] + " " + EnglishNumbers.cardinal(v.toLong())
        }
        // "Henry VIII" -> "Henry the Eighth"; ein einzelnes "I" bleibt das Pronomen
        t = Regex("""\b(\p{Lu}\p{Ll}{2,})\s+([IVX]{2,6}|V|X)(?![\p{L}'’])""").sub(t) { m ->
            if (m.groupValues[1] in enRegnalStop) return@sub null
            val v = Roman.parse(m.groupValues[2])?.takeIf { it in 1..30 } ?: return@sub null
            m.groupValues[1] + " the " + EnglishNumbers.ordinal(v.toLong()).replaceFirstChar { it.uppercaseChar() }
        }
        return t
    }

    private fun pluralizeLastWord(s: String): String =
        if (s.endsWith("y")) s.dropLast(1) + "ies" else s + "s"

    private fun enYears(input: String): String {
        var t = Regex("""(?<![\d.,$€£])(1[1-9]\d{2}|20\d{2})s\b""").replace(input) { m ->
            pluralizeLastWord(EnglishNumbers.year(m.groupValues[1].toInt()))
        }
        t = Regex("""(?<![\d.,$€£])(1[1-9]\d{2}|20\d{2})(?![\d]|[.,]\d)""").replace(t) { m ->
            EnglishNumbers.year(m.value.toInt())
        }
        return t
    }

    private fun enSymbolsBefore(input: String): String {
        var t = input
        t = t.replace(Regex("""§\s?"""), "section ")
        t = t.replace(Regex("""\s&\s"""), " and ")
        t = t.replace(Regex("""(?<=\d)\s?\+\s?(?=\d)"""), " plus ")
        t = t.replace(Regex("""(?<=\d)\s?×\s?(?=\d)"""), " times ")
        t = t.replace("±", " plus or minus ")
        t = t.replace(Regex("""~\s?(?=\d)"""), "about ")
        t = t.replace(Regex("""#\s?(?=\d)"""), "number ")
        t = t.replace(Regex("""(?<=^|[\s(])-(?=\d)"""), "minus ")
        return t
    }

    private fun enNumbers(t: String): String =
        Regex("""(?<![\p{L}\d.,])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?![\p{L}\d])""").sub(t) { m ->
            val int = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@sub null
            EnglishNumbers.decimal(int, m.groupValues[2])
        }

    private fun enSymbolsAfter(input: String): String {
        var t = input
        t = t.replace("$", " dollars").replace("€", " euros").replace("£", " pounds")
        t = t.replace("%", " percent").replace("°", " degrees")
        t = t.replace(Regex("""(?<=\p{L})/(?=\p{L})"""), " ")
        t = t.replace(Regex("""\s[–—]\s"""), ", ")
        return t
    }

    // ================================================================ fuer den Segmentierer

    private val nonFinalPeriod = Regex(
        """(?:(?<![\p{L}])(?:z\.\s?B|d\.\s?h|u\.\s?a|v\.\s?a|o\.\s?Ä|u\.\s?Ä|i\.\s?d\.\s?R|v\.\s?Chr|n\.\s?Chr|""" +
            """usw|bzw|ca|etc|vgl|ggf|evtl|inkl|exkl|zzgl|bspw|insb|sog|bzgl|Nr|Dr|Prof|Hr|Fr|St|Str|S|Kap|Bd|""" +
            """Abb|Tab|Jh|Jhd|Hrsg|geb|gest|Tel|Abs|Art|ff|Std|Min|Sek|Mio|Mrd|Tsd|Mr|Mrs|Ms|Jr|Sr|vs|e\.g|i\.e|""" +
            """approx|No|Vol|Ch|Fig|pp|p|Mt|Ave|Gen|Col|Capt|Lt|Sgt|Rev|Gov|Jan|Febr?|Mär|Mar|Apr|Jun|Jul|Aug|""" +
            """Sept?|Okt|Oct|Nov|Dez|Dec)\.|(?<![\d.,])\d{1,3}\.|(?<![\p{L}])[IVXLC]{1,6}\.|(?<![\p{L}])\p{L}\.)$"""
    )

    /**
     * Endet der Text mit einem Punkt, der kein Satzende ist ("z. B.", "Dr.", "am 3.", "Ludwig XIV.")?
     * Dann gehoert das naechste Stueck noch zum selben Satz. Im Zweifel lieber zusammenlegen: Ein
     * etwas laengeres Stueck schadet nicht, ein zerrissenes Datum schon.
     */
    fun endsWithNonFinalPeriod(s: String): Boolean = nonFinalPeriod.containsMatchIn(s.trimEnd())
}
