package app.simplenarrator.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Übernahme von Regeln aus Librera Reader und @Voice Aloud Reader. */
class RuleImportTest {
    private fun speak(t: String) = Segment.Speak(t)

    /** Libreras Voreinstellung, wie sie in AppState.TTS_REPLACEMENTS steht (org.json mit einfachen Anführungszeichen). */
    private val libreraDefault =
        "{'*[()\\\"«»*”“/\\\\[\\\\]]':' ' , '*[?!:;–|—|―]':'. ' , 'it’s':'it is' , '#bla':'bla disabled' , '*(L|l)ibre.':'\$1ibréra'}"

    @Test
    fun libreraVoreinstellung() {
        val out = SpeechRules.importRules(libreraDefault)
        assertEquals(
            listOf(
                "*(?-i)[()\"«»*”“/\\[\\]]= ",
                "*(?-i)[?!:;–|—|―]=. ",
                "*(?-i)it’s=it is",
                "#*(?-i)bla=bla disabled",
                "*(?-i)(L|l)ibre.=\$1ibréra",
            ),
            out.lines(),
        )
        val rules = SpeechRules.parse(out)
        assertEquals(emptyList<RuleError>(), rules.errors)
        assertEquals(4, rules.size)
        // "Libre." frisst wie bei Librera den Punkt mit (der Punkt im Muster steht für ein beliebiges Zeichen)
        assertEquals(listOf(speak("Ich lese mit Libréra Es ist gut, it is fine.")), rules.apply("Ich lese (mit) Libre! Es ist gut, it’s fine."))
        // Librera ersetzt einfache Regeln schreibungsgenau
        assertEquals(listOf(speak("It’s ok.")), rules.apply("It’s ok?"))
    }

    @Test
    fun libreraSicherungMitFeld() {
        val backup = """{"isEnalbeTTSReplacements":true,"lineTTSReplacements3":"{\"*\\\\bDr\\\\.\":\"Doktor\",\"#Prof.\":\"Professor\"}","x":1}"""
        val out = SpeechRules.importRules(backup)
        assertEquals(listOf("*(?-i)\\bDr\\.=Doktor", "#*(?-i)Prof\\.=Professor"), out.lines())
        assertEquals(listOf(speak("Doktor Müller")), SpeechRules.parse(out).apply("Dr. Müller"))
    }

    @Test
    fun libreraBefehle() {
        val out = SpeechRules.importRules("""{"*Kapitel (\\d+)":"Kapitel ${'$'}1 ttsPAUSE","*^Seite \\d+${'$'}":"ttsSKIP","<p>":"x","Ende":"ttsSTOP","abc256":"x"}""")
        val lines = out.lines()
        assertEquals("*(?-i)Kapitel (\\d+)=Kapitel \$1 [pause]", lines[0])
        assertTrue(lines[1], lines[1].startsWith("#") && lines[1].contains("ttsSKIP"))
        assertTrue(lines[2], lines[2].startsWith("#"))
        assertTrue(lines[3], lines[3].startsWith("#"))
        assertTrue(lines[4], lines[4].startsWith("#"))
        val rules = SpeechRules.parse(out)
        assertEquals(1, rules.size)
        assertEquals(listOf(speak("Kapitel 2"), Segment.Pause(500), speak("Es begann.")), rules.apply("Kapitel 2 Es begann."))
    }

    @Test
    fun voiceRegeldatei() {
        val file = listOf(
            "# Wörterbuch für Librera und @Voice",
            "\"Lib.\" \"Librera\"",
            "*\"(?i)\\b\\Qстрелок\\E\\b\" \"стрело́к\"",
            "*\"(\\d+)\\s*kg\" \"\$1 Kilogramm\"",
            "\" живого \" \" живо́ва \"",
            "\"US\" \"U S\"",
            "",
            "Tee=Ti",
        ).joinToString("\n")
        val out = SpeechRules.importRules(file)
        assertEquals(
            listOf(
                "# Wörterbuch für Librera und @Voice",
                "*(?-i)Lib\\.=Librera",
                "*\\b\\Qстрелок\\E\\b=стрело́к",
                "*(?-i)(\\d+)\\s*kg=\$1 Kilogramm",
                "*(?-i)\\x20живого\\x20= живо́ва ",
                "*(?-i)US=U S",
                "",
                "Tee=Ti",
            ),
            out.lines(),
        )
        val rules = SpeechRules.parse(out)
        assertEquals(emptyList<RuleError>(), rules.errors)
        assertEquals(listOf(speak("Librera wiegt 3 Kilogramm, us nicht U S.")), rules.apply("Lib. wiegt 3kg, us nicht US."))
        assertEquals(listOf(speak("Der стрело́к kam.")), rules.apply("Der СТРЕЛОК kam."))
        assertEquals(listOf(speak("bei живо́ва Leib")), rules.apply("bei живого Leib"))
    }

    @Test
    fun pfeilSchreibweiseAusDerHilfe() {
        val out = SpeechRules.importRules("\"Lib.\" -> \"Librera\"\n\"*(L|l)ib.\" -> \"\$1ibrera\"")
        assertEquals(listOf("*(?-i)Lib\\.=Librera", "*(?-i)(L|l)ib.=\$1ibrera"), out.lines())
    }

    @Test
    fun dollarUndBackslashImEinfachenErsatz() {
        val out = SpeechRules.importRules("\"USD\" \"\$ US\"\n\"a\\\\b\" \"c\\\\d\"")
        val rules = SpeechRules.parse(out)
        assertEquals(emptyList<RuleError>(), rules.errors)
        assertEquals(listOf(speak("5 \$ US")), rules.apply("5 USD"))
        assertEquals(listOf(speak("x c\\\\d")), rules.apply("x a\\\\b"))
    }

    @Test
    fun gleichheitszeichenImRegexWirdMaskiert() {
        val out = SpeechRules.importRules("{\"*a=b\":\"gleich\",\"*(?<=\\\\d)\\\\.(?=\\\\d)\":\",\",\"x=y\":\"z\"}")
        assertEquals(listOf("*(?-i)a\\=b=gleich", "*(?-i)(?<=\\d)\\.(?=\\d)=,", "*(?-i)x\\=y=z"), out.lines())
        val rules = SpeechRules.parse(out)
        assertEquals(emptyList<RuleError>(), rules.errors)
        assertEquals(listOf(speak("gleich 3,5 z")), rules.apply("a=b 3.5 x=y"))
    }

    @Test
    fun eigenesFormatBleibtUnveraendert() {
        val own = "# Kommentar\nKokoro=Ko ko ro\n*\\bdr\\.=Doktor\nwort=[pause:300]"
        assertEquals(own, SpeechRules.importRules(own))
    }

    @Test
    fun kaputtesJsonFaelltAufZeilenZurueck() {
        val broken = "{\"a\":\"b\""
        assertEquals(broken, SpeechRules.importRules(broken))
    }

    @Test
    fun hilfsfunktionen() {
        assertEquals("Dr\\.", RuleImport.literalToRegex("Dr."))
        assertEquals("a\\+b\\(c\\)\\=d", RuleImport.literalToRegex("a+b(c)=d"))
        assertEquals("\\x20ab\\x20\\x20", RuleImport.literalToRegex(" ab  "))
        assertEquals("a\\=b(?=c)", RuleImport.escapeSeparators("a=b(?=c)"))
        assertEquals(listOf("k" to "v", "x" to "1"), RuleImport.LenientJson.parseObject("{ 'k' : \"v\", x: 1, }"))
        assertEquals(listOf("u" to "ä\n"), RuleImport.LenientJson.parseObject("{\"u\":\"\\u00e4\\n\"}"))
    }
}
