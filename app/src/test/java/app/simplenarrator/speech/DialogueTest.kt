package app.simplenarrator.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wörtliche Rede in deutschen und englischen Buchsätzen. */
class DialogueTest {
    private fun d(t: String) = Run(t, true)
    private fun n(t: String) = Run(t, false)

    /** Ein Absatz; prüft nebenbei, dass die Runs jedes Satzes zusammen den Satz ergeben. */
    private fun para(vararg sentences: String): List<List<Run>> {
        val result = Dialogue.detect(sentences.toList())
        assertEquals(sentences.size, result.size)
        sentences.forEachIndexed { i, s -> assertEquals(s, result[i].joinToString("") { it.text }) }
        return result
    }

    private fun one(s: String): List<Run> = para(s).single()

    @Test
    fun deutscheAnfuehrungUndBegleitsatz() {
        assertEquals(
            listOf(d("„Komm her“"), n(", sagte er, "), d("„und setz dich.“")),
            one("„Komm her“, sagte er, „und setz dich.“"),
        )
        assertEquals(listOf(n("Sie sah ihn an und fragte: "), d("„Wohin gehst du?“")), one("Sie sah ihn an und fragte: „Wohin gehst du?“"))
        assertEquals(listOf(d("„Nein!“"), n(" Sie schüttelte den Kopf.")), one("„Nein!“ Sie schüttelte den Kopf."))
    }

    @Test
    fun zitatUeberMehrereSaetze() {
        val r = para("„Komm her.", "Setz dich“, sagte sie leise.", "Er setzte sich.")
        assertEquals(listOf(d("„Komm her.")), r[0])
        assertEquals(listOf(d("Setz dich“"), n(", sagte sie leise.")), r[1])
        assertEquals(listOf(n("Er setzte sich.")), r[2])
    }

    @Test
    fun offenesZitatAmAbsatzendeUndNeuerAbsatz() {
        assertEquals(listOf(d("„Das war der erste Teil meiner Geschichte.")), one("„Das war der erste Teil meiner Geschichte."))
        // Neuer Absatz: Zustand ist zurückgesetzt
        assertEquals(listOf(n("Danach schwiegen beide eine Weile.")), one("Danach schwiegen beide eine Weile."))
        val r = para("„Ich bleibe hier.", "Morgen gehe ich weiter.")
        assertEquals(listOf(d("Morgen gehe ich weiter.")), r[1])
    }

    @Test
    fun guillemetsInBeidenRichtungen() {
        assertEquals(listOf(d("»Wohin?«"), n(", fragte sie.")), one("»Wohin?«, fragte sie."))
        assertEquals(listOf(d("«Bonjour»"), n(", dit-il.")), one("«Bonjour», dit-il."))
        assertEquals(listOf(d("« Allons-y ! »"), n(" cria Paul.")), one("« Allons-y ! » cria Paul."))
        val r = para("»Ich weiß es nicht.", "Vielleicht morgen.«", "Dann ging er.")
        assertEquals(listOf(d("»Ich weiß es nicht.")), r[0])
        assertEquals(listOf(d("Vielleicht morgen.«")), r[1])
        assertEquals(listOf(n("Dann ging er.")), r[2])
    }

    @Test
    fun englischeAnfuehrungMitApostrophen() {
        assertEquals(
            listOf(d("“I don’t know,”"), n(" Peter’s brother said, "), d("“but we’ll see.”")),
            one("“I don’t know,” Peter’s brother said, “but we’ll see.”"),
        )
        assertEquals(listOf(d("‘I can’t go,’"), n(" she said.")), one("‘I can’t go,’ she said."))
        assertEquals(listOf(n("She said: "), d("‘It’s Sam’s turn.’")), one("She said: ‘It’s Sam’s turn.’"))
    }

    @Test
    fun genitivApostrophImZitat() {
        assertEquals(listOf(d("‘The boys’ toys are here,’"), n(" she said.")), one("‘The boys’ toys are here,’ she said."))
        assertEquals(listOf(d("‘Hello’"), n(" and he left.")), one("‘Hello’ and he left."))
    }

    @Test
    fun apostropheOhneZitat() {
        assertEquals(listOf(n("He loved rock ’n’ roll and the ’90s.")), one("He loved rock ’n’ roll and the ’90s."))
        assertEquals(listOf(n("He loved rock ‘n’ roll in the ‘90s.")), one("He loved rock ‘n’ roll in the ‘90s."))
        assertEquals(listOf(n("It’s James’ car, ’tis true, goin’ home.")), one("It’s James’ car, ’tis true, goin’ home."))
        assertEquals(listOf(n("Wie geht’s dir, Hans’ Freund?")), one("Wie geht’s dir, Hans’ Freund?"))
        assertEquals(listOf(n("It's Peter's book, isn't it?")), one("It's Peter's book, isn't it?"))
    }

    @Test
    fun geradeDoppelteAnfuehrungszeichen() {
        assertEquals(
            listOf(d("\"Hello,\""), n(" he said, "), d("\"come in.\"")),
            one("\"Hello,\" he said, \"come in.\""),
        )
        val r = para("\"Wait.", "I'm coming,\" she called.")
        assertEquals(listOf(d("\"Wait.")), r[0])
        assertEquals(listOf(d("I'm coming,\""), n(" she called.")), r[1])
        assertEquals(listOf(d("\"Komm!\""), n(", rief sie.")), one("\"Komm!\", rief sie."))
    }

    @Test
    fun geradeEinfacheAnfuehrungszeichen() {
        assertEquals(listOf(d("'Hello,'"), n(" she said, "), d("'it's late.'")), one("'Hello,' she said, 'it's late.'"))
        assertEquals(listOf(d("'The girls' books are new,'"), n(" he said.")), one("'The girls' books are new,' he said."))
        assertEquals(listOf(n("Rock 'n' roll in the '80s.")), one("Rock 'n' roll in the '80s."))
    }

    @Test
    fun deutscheEinfacheAnfuehrung() {
        assertEquals(listOf(d("‚Wie geht’s?‘"), n(", fragte er.")), one("‚Wie geht’s?‘, fragte er."))
        assertEquals(listOf(d("‚Ja, gern’"), n(", sagte sie.")), one("‚Ja, gern’, sagte sie."))
    }

    @Test
    fun verschachtelt() {
        assertEquals(
            listOf(d("„Er sagte: ‚Komm her!‘ und ging“"), n(", erzählte sie.")),
            one("„Er sagte: ‚Komm her!‘ und ging“, erzählte sie."),
        )
        assertEquals(
            listOf(d("“She told me, ‘Don’t wait,’ and left.”"), n(" Tom sighed.")),
            one("“She told me, ‘Don’t wait,’ and left.” Tom sighed."),
        )
        assertEquals(listOf(d("»Er rief: ›Halt!‹ und lief.«")), one("»Er rief: ›Halt!‹ und lief.«"))
    }

    @Test
    fun titelUndBegriffeBleibenErzaehltext() {
        assertEquals(listOf(n("Er las den »Spiegel« jeden Morgen.")), one("Er las den »Spiegel« jeden Morgen."))
        assertEquals(listOf(n("Das nennt man „Glück“.")), one("Das nennt man „Glück“."))
        assertEquals(listOf(n("He called it “nonsense” and left.")), one("He called it “nonsense” and left."))
        // Satzzeichen oder Satzanfang machen daraus Rede
        assertEquals(listOf(n("Er rief: "), d("„Hilfe“")), one("Er rief: „Hilfe“"))
        assertEquals(listOf(n("Er rief "), d("„Hilfe!“")), one("Er rief „Hilfe!“"))
        assertEquals(listOf(d("„Ja“"), n(", sagte er.")), one("„Ja“, sagte er."))
    }

    @Test
    fun polnischeUndSchwedischeSchlusszeichen() {
        assertEquals(listOf(d("„Tak”"), n(", powiedział.")), one("„Tak”, powiedział."))
        assertEquals(listOf(d("”Hej”"), n(", sa han.")), one("”Hej”, sa han."))
    }

    @Test
    fun satzzeichenZwischenRedenGehenMit() {
        assertEquals(listOf(d("„Komm!“ – „Nein!“")), one("„Komm!“ – „Nein!“"))
        assertEquals(listOf(d("– „Hallo“"), n(", sagte er.")), one("– „Hallo“, sagte er."))
    }

    @Test
    fun ohneRede() {
        assertEquals(listOf(n("Ein ganz normaler Satz ohne Anführung.")), one("Ein ganz normaler Satz ohne Anführung."))
        assertEquals(emptyList<Run>(), Dialogue.detect(listOf("")).single())
        assertEquals(emptyList<List<Run>>(), Dialogue.detect(emptyList()))
    }

    @Test
    fun langerAbsatzAusEinemRoman() {
        val r = para(
            "Der Regen hatte aufgehört.",
            "„Bist du sicher?“, fragte Anna und sah zum Fenster hinaus.",
            "„Ganz sicher.",
            "Wir gehen jetzt“, antwortete Tom.",
            "Sie nickte.",
        )
        assertEquals(listOf(n("Der Regen hatte aufgehört.")), r[0])
        assertEquals(listOf(d("„Bist du sicher?“"), n(", fragte Anna und sah zum Fenster hinaus.")), r[1])
        assertEquals(listOf(d("„Ganz sicher.")), r[2])
        assertEquals(listOf(d("Wir gehen jetzt“"), n(", antwortete Tom.")), r[3])
        assertEquals(listOf(n("Sie nickte.")), r[4])
        assertTrue(r.flatten().none { it.text.isBlank() })
    }
}
