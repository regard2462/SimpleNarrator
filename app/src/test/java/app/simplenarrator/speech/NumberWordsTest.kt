package app.simplenarrator.speech

import org.junit.Assert.assertTrue
import org.junit.Test

/** Zahlwörter (deutsch, englisch) und römische Zahlen. */
class NumberWordsTest {
    private val failures = mutableListOf<String>()
    private val G = GermanNumbers
    private val E = EnglishNumbers
    private fun eq(got: String, want: String) {
        if (got != want) failures += "bekommen '$got', erwartet '$want'"
    }

    private fun verify() = assertTrue(failures.size.toString() + " Abweichung(en):\n" + failures.joinToString("\n"), failures.isEmpty())

    /** Deutsch Grundzahlen */
    @Test
    fun deutschGrundzahlen() {
        eq(G.cardinal(0), "null")
        eq(G.cardinal(1), "eins")
        eq(G.cardinal(16), "sechzehn")
        eq(G.cardinal(17), "siebzehn")
        eq(G.cardinal(21), "einundzwanzig")
        eq(G.cardinal(30), "dreißig")
        eq(G.cardinal(99), "neunundneunzig")
        eq(G.cardinal(100), "hundert")
        eq(G.cardinal(101), "hunderteins")
        eq(G.cardinal(121), "hunderteinundzwanzig")
        eq(G.cardinal(1000), "tausend")
        eq(G.cardinal(1001), "tausendeins")
        eq(G.cardinal(2024), "zweitausendvierundzwanzig")
        eq(G.cardinal(21000), "einundzwanzigtausend")
        eq(G.cardinal(101000), "hunderteintausend")
        eq(G.cardinal(1_000_000), "eine Million")
        eq(G.cardinal(2_300_000), "zwei Millionen dreihunderttausend")
        eq(G.cardinal(1_234_567), "eine Million zweihundertvierunddreißigtausendfünfhundertsiebenundsechzig")
        eq(G.cardinal(3_000_000_000), "drei Milliarden")
        eq(G.cardinal(1, GermanNumbers.One.EINE), "eine")
        eq(G.cardinal(1, GermanNumbers.One.EIN), "ein")
        eq(G.cardinal(-5), "minus fünf")
        verify()
    }

    /** Deutsch Ordnungszahlen / Jahre / Brueche */
    @Test
    fun deutschOrdnungszahlenJahreBrueche() {
        eq(G.ordinal(1), "erste")
        eq(G.ordinal(3, "en"), "dritten")
        eq(G.ordinal(7), "siebte")
        eq(G.ordinal(8), "achte")
        eq(G.ordinal(19), "neunzehnte")
        eq(G.ordinal(20), "zwanzigste")
        eq(G.ordinal(21), "einundzwanzigste")
        eq(G.ordinal(100), "hundertste")
        eq(G.ordinal(101), "hunderterste")
        eq(G.ordinal(1000), "tausendste")
        eq(G.year(1990), "neunzehnhundertneunzig")
        eq(G.year(1900), "neunzehnhundert")
        eq(G.year(1905), "neunzehnhundertfünf")
        eq(G.year(2024), "zweitausendvierundzwanzig")
        eq(G.year(1066), "tausendsechsundsechzig")
        eq(G.decimal(3, "14"), "drei Komma eins vier")
        eq(G.decimal(0, "5"), "null Komma fünf")
        eq(G.fraction(1, 2), "ein halb")
        eq(G.fraction(3, 4), "drei Viertel")
        eq(G.fraction(1, 3), "ein Drittel")
        eq(G.fraction(5, 8), "fünf Achtel")
        eq(G.fraction(1, 100), "ein Hundertstel")
        eq(G.fraction(7, 20), "sieben Zwanzigstel")
        verify()
    }

    /** Englisch */
    @Test
    fun englisch() {
        eq(E.cardinal(21), "twenty-one")
        eq(E.cardinal(115), "one hundred fifteen")
        eq(E.cardinal(1_234_567), "one million two hundred thirty-four thousand five hundred sixty-seven")
        eq(E.ordinal(1), "first")
        eq(E.ordinal(2), "second")
        eq(E.ordinal(3), "third")
        eq(E.ordinal(12), "twelfth")
        eq(E.ordinal(20), "twentieth")
        eq(E.ordinal(21), "twenty-first")
        eq(E.ordinal(100), "one hundredth")
        eq(E.ordinal(8), "eighth")
        eq(E.year(1990), "nineteen ninety")
        eq(E.year(1905), "nineteen oh five")
        eq(E.year(1900), "nineteen hundred")
        eq(E.year(2000), "two thousand")
        eq(E.year(2005), "two thousand five")
        eq(E.year(2010), "twenty ten")
        eq(E.year(2024), "twenty twenty-four")
        eq(E.decimal(3, "14"), "three point one four")
        eq(E.fraction(3, 4), "three quarters")
        eq(E.fraction(5, 8), "five eighths")
        verify()
    }

    /** Roemisch */
    @Test
    fun roemisch() {
        eq("${Roman.parse("XIV")}", "14")
        eq("${Roman.parse("MCMXC")}", "1990")
        eq("${Roman.parse("IIII")}", "null")
        eq("${Roman.parse("VX")}", "null")
        eq("${Roman.parse("DVD")}", "null")
        eq("${Roman.parse("CD")}", "400")
        verify()
    }
}
