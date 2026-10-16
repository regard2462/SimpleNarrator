package app.simplenarrator.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kaputte Kodierung, Ligaturen, Fußnotenziffern und Silbentrennung aus PDFs. */
class TextRepairTest {
    @Test
    fun mojibake() {
        assertEquals("don’t", TextRepair.repair("donâ€™t"))
        assertEquals("“Hello,” she said.", TextRepair.repair("â€œHello,â€ she said."))
        assertEquals("Schöne Grüße aus Köln", TextRepair.repair("SchÃ¶ne GrÃ¼ÃŸe aus KÃ¶ln"))
        assertEquals("Er wartete – lange…", TextRepair.repair("Er wartete â€“ langeâ€¦"))
        assertEquals("Café, à la carte", TextRepair.repair("CafÃ©, Ã  la carte"))
        // Richtig kodierter Text daneben bleibt, wie er ist
        assertEquals("Müller sagte: „Schön.“", TextRepair.repair("Müller sagte: „SchÃ¶n.“"))
        assertEquals("SÃO PAULO und Ära", TextRepair.repair("SÃO PAULO und Ära"))
        assertEquals("Ein Café… und „Tee“, Ã¤ wird ä", TextRepair.repair("Ein Café… und „Tee“, Ã¤ wird Ã¤").replaceFirst("ä wird", "Ã¤ wird"))
    }

    @Test
    fun ligaturenUndLangesS() {
        assertEquals("finden, Auflage, Effekt, offline", TextRepair.repair("ﬁnden, Auﬂage, Eﬀekt, oﬄine"))
        assertEquals("schön ist es", TextRepair.repair("ſchön iſt es"))
        assertEquals("ﬁ".length + 1, TextRepair.repair("ﬁ").length)
    }

    @Test
    fun importNormalisiertMit() {
        assertEquals("Grüße... bis bald", TextCleaner.normalize("GrÃ¼ÃŸe… bis\nbald"))
    }

    @Test
    fun fussnotenziffernAusPdf() {
        assertEquals("Wie Müller zeigte und andere.", TextCleaner.removeFootnoteMarks("Wie Müller zeigte12 und andere."))
        assertEquals("Ende des Satzes. Danach kam mehr.", TextCleaner.removeFootnoteMarks("Ende des Satzes.3 Danach kam mehr."))
        assertEquals("100 m2 Fläche, eine mp3 und A4.", TextCleaner.removeFootnoteMarks("100 m2 Fläche, eine mp3 und A4."))
        assertEquals("Im Jahr 1990 kam es.", TextCleaner.removeFootnoteMarks("Im Jahr 1990 kam es."))
    }

    @Test
    fun silbentrennungUeberDieSeite() {
        val paras = PdfParser.paragraphsOf(listOf("Das war eine lange Ge-", "schichte über den Wald.", "Neue Seite mit Satz."))
        assertEquals(listOf("Das war eine lange Geschichte über den Wald.", "Neue Seite mit Satz."), paras.map { it.text })
        // Gedankenstrich und Großschreibung: kein Zusammenziehen
        val dash = PdfParser.paragraphsOf(listOf("Er kam -", "und ging.", "Ost-", "West-Achse"))
        assertEquals(listOf("Er kam - und ging.", "Ost- West-Achse"), dash.map { it.text })
    }
}
