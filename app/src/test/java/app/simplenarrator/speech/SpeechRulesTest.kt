package app.simplenarrator.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aussprache-Regeln: Wörter, reguläre Ausdrücke, Entfernen, Pausen, Kommentare, Fehler. */
class SpeechRulesTest {
    private fun speak(t: String) = Segment.Speak(t)
    private fun pause(ms: Int) = Segment.Pause(ms)

    /** Ergebnis als ein Text, wenn keine Pausen entstehen. */
    private fun text(rules: String, input: String): String {
        val r = SpeechRules.parse(rules)
        assertEquals("Fehler: ${r.errors}", emptyList<RuleError>(), r.errors)
        val segs = r.apply(input)
        assertTrue("unerwartete Pause in $segs", segs.all { it is Segment.Speak })
        return segs.joinToString(" ") { (it as Segment.Speak).text }
    }

    @Test
    fun ganzesWortOhneGrossKlein() {
        assertEquals("Ko ko ro liest vor.", text("Kokoro=Ko ko ro", "kokoro liest vor."))
        assertEquals("Kokorox bleibt.", text("Kokoro=Ko ko ro", "Kokorox bleibt."))
        assertEquals("Er sagte Nju Jork.", text("New York=Nju Jork", "Er sagte new york."))
    }

    @Test
    fun umlauteZaehlenAlsBuchstaben() {
        val rules = "Müller=Müla\nBär=Behr\nÄrger=Erger"
        assertEquals("Müla und Müla trafen Müllerin.", text(rules, "MÜLLER und müller trafen Müllerin."))
        assertEquals("Der Behr, der Eisbär und die Bären.", text(rules, "Der Bär, der Eisbär und die Bären."))
        assertEquals("Viel Erger mit ärgerlich.", text(rules, "Viel Ärger mit ärgerlich."))
        assertEquals("Fuß Strasse Straßen", text("Straße=Strasse", "Fuß Straße Straßen"))
        assertEquals("Ü-Ei", text("über=Ü", "Über-Ei"))
    }

    @Test
    fun abkuerzungMitPunkt() {
        // Mit \b…\b (bisher) traf "Dr." vor einem Leerzeichen nie; jetzt schon.
        assertEquals("Doktor Müller kam.", text("Dr.=Doktor", "Dr. Müller kam."))
        assertEquals("Ge Em Be Ha-Ge Em Be Ha", text("GmbH=Ge Em Be Ha", "GmbH-GmbH"))
        assertEquals("Ge Em Be Ha und Co.", text("GmbH=Ge Em Be Ha", "GmbH und Co."))
    }

    @Test
    fun wieDasBisherigeLexikon() {
        val raw = "Kokoro=Kokoro\nSQL=sequel\nThorsten=Torsten\n  Bär = Behr  \nrecord=rekord"
        // Erwartung wie beim früheren Wörterbuch: ganze Wörter, Groß- und Kleinschreibung egal
        val cases = listOf(
            "SQL ist eine Sprache, sql auch." to "sequel ist eine Sprache, sequel auch.",
            "Thorsten und der Bär lesen SQLite." to "Torsten und der Behr lesen SQLite.",
            "A record, a Record and records." to "A rekord, a rekord and records.",
            "Ohne Treffer bleibt alles gleich." to "Ohne Treffer bleibt alles gleich.",
        )
        val rules = SpeechRules.parse(raw)
        for ((input, expected) in cases) {
            assertEquals(listOf(speak(expected)), rules.apply(input))
        }
    }

    @Test
    fun spaetereZeileGewinnt() {
        assertEquals("Ti trinken", text("Tee=Tea\nTee=Ti", "Tee trinken"))
    }

    @Test
    fun regelnWirkenNacheinander() {
        assertEquals("C", text("A=B\nB=C", "A"))
    }

    @Test
    fun dollarImEinfachenErsatzIstWoertlich() {
        assertEquals("Er hat 5 \$US.", text("Dollar=\$US", "Er hat 5 Dollar."))
    }

    @Test
    fun regexMitGruppen() {
        assertEquals("Es wiegt 5 Kilogramm.", text("*(\\d+)\\s*kg\\b=\$1 Kilogramm", "Es wiegt 5kg."))
        assertEquals("Er schrieb 30 Prozent.", text("*(?<zahl>\\d+)\\s?%=\${zahl} Prozent", "Er schrieb 30%."))
        assertEquals("Kapitel drei, Kapitel vier", text("*\\bKap\\.\\s*(\\w+)=Kapitel \$1", "Kap. drei, kap.vier"))
    }

    @Test
    fun regexOhneGrossKleinUndMitUnicode() {
        assertEquals("Doktor Müller", text("*\\bdr\\.(?=\\s)=Doktor", "DR. Müller"))
        // \w und \b kennen Umlaute
        assertEquals("Mädlein und Fräulein", text("*\\b(\\w+)chen\\b=\$1lein", "Mädchen und Fräuchen"))
        assertEquals("Öl Ölung", text("*\\böl\\b=Öl", "öl Ölung"))
    }

    @Test
    fun regexSchreibungsgenauMitFlag() {
        assertEquals("U S und us", text("*(?-i)\\bUS\\b=U S", "US und us"))
        assertEquals("U S und U S", text("*\\bUS\\b=U S", "US und us"))
    }

    @Test
    fun gleichheitszeichenImMuster() {
        assertEquals("3,5 und 7,25", text("*(?<=\\d)\\.(?=\\d)=,", "3.5 und 7.25"))
        assertEquals("a gleich b", text("*\\==gleich", "a = b"))
        assertEquals("x ist y", text("*[=]=ist", "x = y"))
    }

    @Test
    fun entfernen() {
        assertEquals("Ein Satz mit Fußnote.", text("*\\[\\d+]=", "Ein Satz [12] mit Fußnote[3]."))
        assertEquals("Hier steht Text.", text("Seite=[skip]", "Hier Seite steht Text."))
        assertEquals("Hier steht Text.", text("*\\s*\\(siehe [^)]*\\)=[SKIP]", "Hier steht (siehe oben) Text."))
    }

    @Test
    fun ersatzNurAusLeerzeichen() {
        assertEquals("Hallo Welt und mehr", text("*[()]= ", "Hallo(Welt)und mehr"))
    }

    @Test
    fun pausenAusRegeln() {
        val r = SpeechRules.parse("*;=[pause]\n*Kapitel (\\d+)\\.?=Kapitel \$1[pause:800]")
        assertEquals(listOf(speak("Erst das"), pause(500), speak("dann das.")), r.apply("Erst das; dann das."))
        assertEquals(listOf(speak("Kapitel 3"), pause(800), speak("Der Anfang.")), r.apply("Kapitel 3. Der Anfang."))
        // Ganze Zeile als Pause
        assertEquals(listOf(pause(300)), SpeechRules.parse("*^\\*\\s*\\*\\s*\\*$=[pause:300]").apply("* * *"))
        // Pause und Text in einfacher Regel
        assertEquals(listOf(speak("Akt eins"), pause(1000), speak("beginnt")), SpeechRules.parse("Akt=Akt eins [pause:1000]").apply("Akt beginnt"))
    }

    @Test
    fun pausenImBuchtext() {
        val r = SpeechRules.EMPTY
        assertEquals(listOf(speak("Er wartete."), pause(1200), speak("Dann ging er.")), r.apply("Er wartete.[pause:1200] Dann ging er."))
        assertEquals(listOf(speak("Eins"), pause(500), speak("zwei")), r.apply("Eins [pause] zwei"))
        assertEquals(listOf(speak("Eins"), pause(300), speak("zwei")), r.apply("Eins [PAUSE: 300 ms] zwei"))
        assertEquals(listOf(pause(400), speak("Anfang")), r.apply("[pause:400]Anfang"))
        // Regeln treffen keine Pausenmarken und arbeiten auf beiden Seiten
        val rules = SpeechRules.parse("*\\d+=Zahl")
        assertEquals(listOf(speak("Zahl"), pause(800), speak("Zahl")), rules.apply("12[pause:800]34"))
    }

    @Test
    fun pausenGrenzenUndZusammenlegen() {
        val r = SpeechRules.EMPTY
        assertEquals(listOf(speak("a"), pause(5000), speak("b")), r.apply("a[pause:99999]b"))
        assertEquals(listOf(speak("a"), pause(5000), speak("b")), r.apply("a[pause:123456789012345678901234567890]b"))
        assertEquals(listOf(speak("a b")), r.apply("a[pause:0]b"))
        assertEquals(listOf(speak("a"), pause(700), speak("b")), r.apply("a[pause:300][pause:400]b"))
        assertEquals(listOf(speak("a"), pause(5000), speak("b")), r.apply("a[pause:3000] [pause:4000]b"))
    }

    @Test
    fun satzzeichenBleibenVorDerPause() {
        val r = SpeechRules.EMPTY
        assertEquals(listOf(speak("Er kam."), pause(800)), r.apply("Er kam[pause:800]."))
        assertEquals(listOf(speak("Sie fragte?!"), pause(500)), r.apply("Sie fragte[pause] ?!"))
        assertEquals(listOf(pause(500), speak("Hallo")), r.apply("„[pause]Hallo"))
    }

    @Test
    fun kommentareUndLeereZeilen() {
        val raw = "# Kopfzeile\n\n#Kokoro=Foo\n   # eingerückt\n#*\\d+=Zahl\nTee=Ti\n"
        val r = SpeechRules.parse(raw)
        assertEquals(emptyList<RuleError>(), r.errors)
        assertEquals(1, r.size)
        assertEquals(listOf(speak("Kokoro trinkt 5 Ti")), r.apply("Kokoro trinkt 5 Tee"))
    }

    @Test
    fun fehlerhafteZeilenWerdenGemeldet() {
        val raw = listOf(
            "Tee=Ti",              // 1 gut
            "*(abc=x",             // 2 Regex kaputt
            "Hallo",               // 3 kein =
            "*abc",                // 4 kein =
            "=nichts",             // 5 leeres Muster
            "*=nichts",            // 6 leeres Muster
            "*(a)b=\$2",           // 7 Gruppe fehlt
            "*(a)b=\${name}",      // 8 benannte Gruppe fehlt
            "*[a-=x",              // 9 Klasse offen, damit auch kein =
            "Kaffee=Kafe",         // 10 gut
        ).joinToString("\n")
        val r = SpeechRules.parse(raw)
        assertEquals(2, r.size)
        val byLine = r.errors.associate { it.line to it.kind }
        assertEquals(RuleError.Kind.INVALID_REGEX, byLine[2])
        assertEquals(RuleError.Kind.MISSING_SEPARATOR, byLine[3])
        assertEquals(RuleError.Kind.MISSING_SEPARATOR, byLine[4])
        assertEquals(RuleError.Kind.EMPTY_PATTERN, byLine[5])
        assertEquals(RuleError.Kind.EMPTY_PATTERN, byLine[6])
        assertEquals(RuleError.Kind.INVALID_REPLACEMENT, byLine[7])
        assertEquals(RuleError.Kind.INVALID_REPLACEMENT, byLine[8])
        assertEquals(RuleError.Kind.MISSING_SEPARATOR, byLine[9])
        assertEquals(8, r.errors.size)
        assertTrue(r.errors.first { it.line == 2 }.detail.isNotBlank())
        assertEquals("*(abc=x", r.errors.first { it.line == 2 }.text)
        // Die guten Regeln wirken trotzdem
        assertEquals(listOf(speak("Ti und Kafe")), r.apply("Tee und Kaffee"))
    }

    @Test
    fun ungueltigeRegexInDerMitteBrichtNichtsAb() {
        val r = SpeechRules.parse("*[=x\nA=B")
        assertEquals(1, r.errors.size)
        assertEquals(listOf(speak("B")), r.apply("A"))
    }

    @Test
    fun leererText() {
        assertEquals(emptyList<Segment>(), SpeechRules.EMPTY.apply(""))
        assertEquals(emptyList<Segment>(), SpeechRules.EMPTY.apply("   "))
        assertEquals(emptyList<Segment>(), SpeechRules.parse("*.+=").apply("Alles weg"))
        assertEquals(emptyList<Segment>(), SpeechRules.parse("*^Seite \\d+$=").apply("Seite 12"))
    }

    @Test
    fun leerraumWirdZusammengefasst() {
        assertEquals(listOf(speak("Zu viel Raum.")), SpeechRules.EMPTY.apply("  Zu   viel\n\tRaum. "))
    }

    @Test
    fun privateMarkenImTextStoerenNicht() {
        assertEquals(listOf(speak("a12b")), SpeechRules.EMPTY.apply("a12b"))
    }

    @Test
    fun trennzeichenSuche() {
        assertEquals(1, SpeechRules.regexSeparator("a=b"))
        assertEquals(4, SpeechRules.regexSeparator("a\\=b=c"))
        assertEquals(8, SpeechRules.regexSeparator("(?<=\\d)x=y"))
        assertEquals(7, SpeechRules.regexSeparator("x(?=\\d)=y"))
        assertEquals(3, SpeechRules.regexSeparator("[=]=y"))
        assertEquals(7, SpeechRules.regexSeparator("\\Qa=b\\E=y"))
        assertEquals(-1, SpeechRules.regexSeparator("abc"))
    }
}
