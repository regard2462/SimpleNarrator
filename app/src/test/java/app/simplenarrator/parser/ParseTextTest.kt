package app.simplenarrator.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Eingefügter Text: Titel aus der ersten Zeile, Absätze auch bei einfachen Zeilenumbrüchen. */
class ParseTextTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun shortFirstLineBecomesTitle() {
        val book = TxtParser.parseText("Der Brunnen\n\nDer König ging mit seiner Tochter in den Wald und sie fanden dort einen Brunnen.")
        assertEquals("Der Brunnen", book.title)
        assertEquals("", book.author)
        assertEquals(1, book.chapters.size)
        assertEquals("Anfang", book.chapters[0].title)
    }

    @Test
    fun longFirstLineGivesFirstWords() {
        val first = "Es war einmal ein König, der hatte eine Tochter, und die war so schön, dass die Sonne selbst sich wunderte, so oft sie ihr ins Gesicht schien."
        val book = TxtParser.parseText(first + "\n\nZweiter Absatz.")
        assertTrue(book.title, book.title.endsWith(" …"))
        assertTrue(book.title, book.title.startsWith("Es war einmal ein König, der hatte"))
        assertTrue(book.title.length <= 80)
    }

    @Test
    fun explicitTitleWins() {
        val book = TxtParser.parseText("# Kapitel eins\n\nText im ersten Kapitel, der lang genug ist für einen Satz.", title = "  Mein Text ")
        assertEquals("Mein Text", book.title)
    }

    @Test
    fun markdownHeadingAsTitleWithoutHashes() {
        val book = TxtParser.parseText("# Notizen zur Reise\n\nWir fuhren am Morgen los und kamen am Abend an.")
        assertEquals("Notizen zur Reise", book.title)
    }

    @Test
    fun singleNewlineParagraphsFromWebCopies() {
        val p1 = "It was a bright cold day in April, and the clocks were striking thirteen when he came home from work in the evening."
        val p2 = "The hallway smelt of boiled cabbage and old rag mats, and he walked up the stairs slowly, resting several times on the way."
        val p3 = "On each landing, opposite the lift shaft, the poster with the enormous face gazed from the wall at everyone who passed by."
        val book = TxtParser.parseText("$p1\r\n$p2\n$p3")
        assertEquals(3, book.chapters[0].paragraphs.size)
        assertEquals("Beginning", book.chapters[0].title)
    }

    @Test
    fun hardWrappedLinesStayTogether() {
        val text = "Es war einmal ein König,\nder hatte eine Tochter,\nund die war sehr schön."
        val book = TxtParser.parseText(text)
        assertEquals(1, book.chapters[0].paragraphs.size)
    }

    @Test
    fun fileImportKeepsEmptyTitle() {
        val f = tmp.newFile("buch.txt").apply { writeText("Erste Zeile\n\nDer König ging mit seiner Tochter in den Wald.") }
        assertEquals("", TxtParser.parse(f).title)
    }
}
