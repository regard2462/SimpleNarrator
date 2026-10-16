package app.simplenarrator.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Satzgrenzen: Punkte nach Ordnungszahlen, Titeln und Abkürzungen beenden keinen Satz. */
class SegmenterTest {
    private val failures = mutableListOf<String>()
    private fun seg(text: String, want: Int, lang: String = "de") {
        val got = Segmenter.split(text, lang)
        if (got.size != want) failures += "erwartet $want Sätze, bekommen ${got.size}: ${got.joinToString(" | ")}"
    }

    private fun verify() = assertTrue(failures.size.toString() + " Abweichung(en):\n" + failures.joinToString("\n"), failures.isEmpty())

    /** Satzgrenzen */
    @Test
    fun satzgrenzen() {
        seg("Die große Konferenz in Berlin begann am 3. Mai. Danach ging es weiter.", 2)
        seg("Gestern traf ich in der Stadt Herrn Dr. Müller aus dem Nachbarhaus. Er war gut gelaunt.", 2)
        seg("Das gilt für viele Obstsorten, z. B. Äpfel und Birnen aus dem Garten. Der Rest nicht.", 2)
        seg("Unter der langen Herrschaft von Ludwig XIV. blühte Frankreich auf. Dann kam der Umbruch.", 2)
        seg("Er kam am späten Abend herein. Sie stand sofort vom Sofa auf. Beide schwiegen lange Zeit miteinander.", 3)
        seg("Mr. Smith met Dr. Jones at the station. They talked.", 2, "en")
        verify()
    }

    /** Kurze Sätze und Repliken bleiben einzeln, damit zwischen ihnen die Satzpause liegt. */
    @Test
    fun kurzeSaetzeBleibenEinzeln() {
        seg("Ja. Nein. Vielleicht.", 3)
        seg("„Kommst du?“ „Gleich.“", 2)
        seg("\"Hi.\" \"Hello.\" \"How are you?\"", 3, "en")
        verify()
    }

    /** Der Begleitsatz gehört zur wörtlichen Rede, auch wenn ein Ausrufe- oder Fragezeichen davorsteht. */
    @Test
    fun begleitsatzBleibtBeiDerRede() {
        seg("„Lass mich rein!“ unterbrach ich.", 1)
        seg("„Komm her!“, rief er.", 1)
        seg("„Wer da?“ fragte sie leise.", 1)
        seg("\"Hey, let me in!\" I interrupted.", 1, "en")
        seg("\"Stop!\" John cried.", 1, "en")
        seg("\"Run!\" he shouted.", 1, "en")
        // Eine Handlung danach ist ein eigener Satz
        seg("\"I know.\" She smiled.", 2, "en")
        seg("\"Stop!\" The door slammed shut.", 2, "en")
        verify()
    }

    /** Zu kurze Stücke wie "I..." hängen am nächsten Satz, statt wegzufallen. */
    @Test
    fun kurzeStueckeGehenNichtVerloren() {
        assertEquals(listOf("I... I do not know."), Segmenter.split("I... I do not know.", "en"))
        assertEquals(listOf("Ich… ich weiß nicht."), Segmenter.split("Ich… ich weiß nicht.", "de"))
        assertEquals(listOf("Well...", "Maybe tomorrow."), Segmenter.split("Well... Maybe tomorrow.", "en"))
        assertEquals(listOf("He said no. A!"), Segmenter.split("He said no. A!", "en"))
    }

    @Test
    fun trennzeilenBleibenSeitenzahlenNicht() {
        assertEquals(listOf("* * *"), Segmenter.split("* * *", "de"))
        assertEquals(listOf("———"), Segmenter.split("———", "de"))
        assertEquals(listOf("⁂"), Segmenter.split("⁂", "de"))
        assertEquals(emptyList<String>(), Segmenter.split("12", "de"))
        assertEquals(emptyList<String>(), Segmenter.split("–", "de"))
        assertTrue(Segmenter.isSceneBreak("*  *  *"))
        assertTrue(!Segmenter.isSceneBreak("Kapitel *"))
    }
}
