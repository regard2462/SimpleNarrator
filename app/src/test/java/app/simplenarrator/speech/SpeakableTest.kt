package app.simplenarrator.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** Schreibweisen, die espeak-ng buchstabiert oder ohne Pause liest (mit piper/espeak-ng geprüft). */
class SpeakableTest {
    private fun de(s: String) = Speakable.apply(s, "de")
    private fun en(s: String) = Speakable.apply(s, "en")

    @Test
    fun stottern() {
        assertEquals("What?", en("W-what?"))
        assertEquals("Wait, what?", en("Wait, wh-what?"))
        assertEquals("No.", en("N-no."))
        assertEquals("but", en("b-b-but"))
        assertEquals("I, I don't know.", en("I-I don't know."))
        assertEquals("Nein, ich will nicht.", de("N-nein, i-ich will nicht."))
        // Wörter mit Bindestrich bleiben
        assertEquals("Ein Stück in D-Dur am T-Träger.", de("Ein Stück in D-Dur am T-Träger."))
        assertEquals("Eine E-Mail mit U-Bahn-Plan.", de("Eine E-Mail mit U-Bahn-Plan."))
        assertEquals("a well-known x-ray", en("a well-known x-ray"))
    }

    @Test
    fun gedankenstricheUndStriche() {
        assertEquals("It was – how shall I say – odd.", en("It was--how shall I say--odd."))
        assertEquals("Er war – wie soll ich sagen – seltsam.", de("Er war—wie soll ich sagen—seltsam."))
        assertEquals("But–", en("But--"))
        assertEquals("1990–2000", en("1990--2000"))
        assertEquals("Rosen sind rot, Veilchen sind blau", de("Rosen sind rot | Veilchen sind blau"))
        assertEquals("Er war, wie soll ich sagen, seltsam.", Normalizer.normalize("Er war—wie soll ich sagen—seltsam.", "de"))
    }

    @Test
    fun interjektionenEnglisch() {
        assertEquals("Sch! Be quiet.", en("Shhh! Be quiet."))
        assertEquals("Humph.", en("Hmph."))
        assertEquals("Hmm.", en("Hm."))
        assertEquals("Hmm, maybe.", en("Hmmm, maybe."))
        assertEquals("Hmm-hmm.", en("Mm-hmm."))
        assertEquals("Hmm-hmm.", en("Mhm."))
        assertEquals("Burr, it's cold.", en("Brrr, it's cold."))
        assertEquals("Gurr.", en("Grrr."))
        assertEquals("Pfuh.", en("Pfft."))
        assertEquals("Psst, over here.", en("Pssst, over here."))
        assertEquals("He snored.", en("He snored. Zzz."))
        // Einheiten hinter Zahlen bleiben Einheiten
        assertEquals("5 mm and 3 hm", en("5 mm and 3 hm"))
        // Wörter, die nur ähnlich anfangen, bleiben
        assertEquals("She shrugged, shush is a word.", en("She shrugged, shush is a word."))
    }

    @Test
    fun interjektionenDeutsch() {
        assertEquals("Pist! Sei still.", de("Pst! Sei still."))
        assertEquals("Pischt!", de("Pscht!"))
        assertEquals("Pischt, schlaf jetzt.", de("Schhh, schlaf jetzt."))
        assertEquals("Hm.", de("Hmm."))
        assertEquals("Hm-hm.", de("Mhm."))
        assertEquals("Humpf.", de("Hmpf."))
        assertEquals("Bürr, ist das kalt.", de("Brr, ist das kalt."))
        assertEquals("Gürr.", de("Grrr."))
        assertEquals("Pah.", de("Pff."))
        assertEquals("so ein Unsinn.", de("Tss, so ein Unsinn."))
        assertEquals("5 mm Regen", de("5 mm Regen"))
        assertEquals("Ähm, ja. Hä?", de("Ähm, ja. Hä?"))
    }

    /** Supertonic liest Buchstaben selbst: Interjektionen bleiben, Stottern und Striche werden trotzdem geglättet. */
    @Test
    fun ohneEspeakBleibenInterjektionen() {
        assertEquals("Pst! Nein – jetzt nicht.", Speakable.apply("Pst! N-nein--jetzt nicht.", "de", interjections = false))
        assertEquals("Shhh! What?", Normalizer.normalize("Shhh! W-what?", "en", espeak = false))
        assertEquals("Sch! What?", Normalizer.normalize("Shhh! W-what?", "en"))
    }
}
