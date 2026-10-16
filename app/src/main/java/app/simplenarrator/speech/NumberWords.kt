package app.simplenarrator.speech

/**
 * Zahlen als gesprochene Woerter. Reine Funktionen, ohne Android - damit testbar.
 */
object GermanNumbers {

    /** Form einer auf 1 endenden Zahl: "eins" allein, "ein"/"eine" vor einem Nomen. */
    enum class One { STANDALONE, EIN, EINE }

    private val ones = arrayOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
        "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val tens = arrayOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    private val smallOrdinal = arrayOf(
        "", "erst", "zweit", "dritt", "viert", "fünft", "sechst", "siebt", "acht", "neunt",
        "zehnt", "elft", "zwölft", "dreizehnt", "vierzehnt", "fünfzehnt", "sechzehnt", "siebzehnt", "achtzehnt", "neunzehnt",
    )

    private fun below100(n: Int, final: One): String = when {
        n == 1 -> when (final) { One.STANDALONE -> "eins"; One.EIN -> "ein"; One.EINE -> "eine" }
        n < 20 -> ones[n]
        else -> {
            val u = n % 10
            val t = n / 10
            if (u == 0) tens[t] else (if (u == 1) "ein" else ones[u]) + "und" + tens[t]
        }
    }

    /** 1..999 als ein zusammengeschriebenes Wort. */
    private fun below1000(n: Int, final: One): String {
        val h = n / 100
        val r = n % 100
        val sb = StringBuilder()
        if (h > 0) sb.append(if (h == 1) "hundert" else ones[h] + "hundert")
        if (r > 0) sb.append(below100(r, final))
        return sb.toString()
    }

    /** Grundzahl. Bis 999.999 ein Wort, Millionen und darueber getrennt ("zwei Millionen dreihunderttausend"). */
    fun cardinal(n: Long, final: One = One.STANDALONE): String {
        if (n == 0L) return "null"
        if (n < 0) return "minus " + cardinal(-n, final)
        val parts = mutableListOf<String>()
        var rest = n
        val scales = listOf(
            1_000_000_000_000L to ("Billion" to "Billionen"),
            1_000_000_000L to ("Milliarde" to "Milliarden"),
            1_000_000L to ("Million" to "Millionen"),
        )
        for ((value, names) in scales) {
            val q = rest / value
            if (q > 0) {
                parts += if (q == 1L) "eine ${names.first}" else "${cardinal(q, One.EINE)} ${names.second}"
                rest %= value
            }
        }
        if (rest > 0) {
            val th = (rest / 1000).toInt()
            val r = (rest % 1000).toInt()
            val sb = StringBuilder()
            if (th > 0) sb.append(if (th == 1) "tausend" else below1000(th, One.EIN) + "tausend")
            if (r > 0) sb.append(below1000(r, final))
            parts += sb.toString()
        }
        return parts.joinToString(" ")
    }

    /** Ordnungszahl-Stamm ohne Endung: 3 -> "dritt", 21 -> "einundzwanzigst", 101 -> "hunderterst". */
    fun ordinalStem(n: Long): String {
        val r = (n % 100).toInt()
        return if (r in 1..19) {
            val prefix = n - r
            (if (prefix > 0) cardinal(prefix) else "") + smallOrdinal[r]
        } else {
            cardinal(n) + "st"
        }
    }

    fun ordinal(n: Long, ending: String = "e"): String = ordinalStem(n) + ending

    /** Jahreszahl: 1100-1999 in Hunderterpaaren ("neunzehnhundertneunzig"), sonst als Grundzahl. */
    fun year(n: Int): String =
        if (n in 1100..1999) {
            val lo = n % 100
            cardinal((n / 100).toLong()) + "hundert" + (if (lo > 0) cardinal(lo.toLong()) else "")
        } else {
            cardinal(n.toLong())
        }

    /** Nachkommastellen werden im Deutschen einzeln gesprochen: 3,14 -> "drei Komma eins vier". */
    fun decimal(intPart: Long, digits: String): String {
        val head = cardinal(intPart)
        if (digits.isEmpty()) return head
        return head + " Komma " + digits.map { cardinal((it - '0').toLong()) }.joinToString(" ")
    }

    /** Bruch: 1/2 -> "ein halb", 3/4 -> "drei Viertel", 5/8 -> "fünf Achtel". */
    fun fraction(num: Long, den: Long): String {
        if (den == 2L) return if (num == 1L) "ein halb" else "${cardinal(num)} halbe"
        val name = ordinalStem(den).replaceFirstChar { it.uppercase() } + "el"
        return "${cardinal(num, One.EIN)} $name"
    }

    fun digits(s: String): String = s.filter { it.isDigit() }.map { cardinal((it - '0').toLong()) }.joinToString(" ")

    val months = arrayOf(
        "", "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
}

object EnglishNumbers {

    private val ones = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun below100(n: Int): String = when {
        n < 20 -> ones[n]
        n % 10 == 0 -> tens[n / 10]
        else -> tens[n / 10] + "-" + ones[n % 10]
    }

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return when {
            h == 0 -> below100(r)
            r == 0 -> "${ones[h]} hundred"
            else -> "${ones[h]} hundred ${below100(r)}"
        }
    }

    fun cardinal(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "minus " + cardinal(-n)
        val parts = mutableListOf<String>()
        var rest = n
        for ((value, name) in listOf(
            1_000_000_000_000L to "trillion", 1_000_000_000L to "billion",
            1_000_000L to "million", 1_000L to "thousand",
        )) {
            val q = rest / value
            if (q > 0) {
                parts += "${cardinal(q)} $name"
                rest %= value
            }
        }
        if (rest > 0) parts += below1000(rest.toInt())
        return parts.joinToString(" ")
    }

    private val irregularOrdinal = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
    )

    /** Ordnungszahl: nur das letzte Teilwort wird umgeformt (twenty-one -> twenty-first). */
    fun ordinal(n: Long): String {
        val words = cardinal(n)
        val cut = maxOf(words.lastIndexOf(' '), words.lastIndexOf('-'))
        val head = if (cut >= 0) words.substring(0, cut + 1) else ""
        val last = if (cut >= 0) words.substring(cut + 1) else words
        val ord = irregularOrdinal[last] ?: when {
            last.endsWith("y") -> last.dropLast(1) + "ieth"
            else -> last + "th"
        }
        return head + ord
    }

    /** 1990 -> "nineteen ninety", 1905 -> "nineteen oh five", 2005 -> "two thousand five", 2024 -> "twenty twenty-four". */
    fun year(n: Int): String {
        if (n in 2000..2009) return if (n == 2000) "two thousand" else "two thousand " + cardinal((n - 2000).toLong())
        if (n !in 1100..2099) return cardinal(n.toLong())
        val hi = n / 100
        val lo = n % 100
        return when {
            lo == 0 -> "${cardinal(hi.toLong())} hundred"
            lo < 10 -> "${cardinal(hi.toLong())} oh ${cardinal(lo.toLong())}"
            else -> "${cardinal(hi.toLong())} ${cardinal(lo.toLong())}"
        }
    }

    fun decimal(intPart: Long, digits: String): String {
        val head = cardinal(intPart)
        if (digits.isEmpty()) return head
        return head + " point " + digits.map { cardinal((it - '0').toLong()) }.joinToString(" ")
    }

    fun fraction(num: Long, den: Long): String = when {
        den == 2L -> if (num == 1L) "one half" else "${cardinal(num)} halves"
        den == 4L -> if (num == 1L) "one quarter" else "${cardinal(num)} quarters"
        else -> "${cardinal(num)} ${ordinal(den)}${if (num == 1L) "" else "s"}"
    }

    fun digits(s: String): String = s.filter { it.isDigit() }.map { cardinal((it - '0').toLong()) }.joinToString(" ")

    val months = arrayOf(
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}

/** Roemische Zahlen, mit Pruefung auf gueltige Schreibweise (sonst null). */
object Roman {
    private val values = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
        50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )

    fun toRoman(n: Int): String {
        var rest = n
        val sb = StringBuilder()
        for ((v, s) in values) while (rest >= v) { sb.append(s); rest -= v }
        return sb.toString()
    }

    /** Nur kanonische Schreibweisen gelten: "XIV" ja, "IIII" oder "VX" nein. */
    fun parse(s: String): Int? {
        if (s.isEmpty() || s.any { it !in "IVXLCDM" }) return null
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in s.indices) {
            val v = map.getValue(s[i])
            val next = if (i + 1 < s.length) map.getValue(s[i + 1]) else 0
            total += if (v < next) -v else v
        }
        return if (total > 0 && toRoman(total) == s) total else null
    }
}
