package app.simplenarrator.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reintext aus Project Gutenberg: Vorspann, gedrucktes Inhaltsverzeichnis, Kapitelerkennung. */
class TxtGutenbergTest {
    private val body = "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do."

    private val pg = """
        The Project Gutenberg eBook of Alice's Adventures in Wonderland

        This ebook is for the use of anyone anywhere in the United States and
        most other parts of the world at no cost and with almost no restrictions.

        Title: Alice's Adventures in Wonderland

        Author: Lewis Carroll

        *** START OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES IN WONDERLAND ***

        Produced by Arthur DiBianca and David Widger

        ALICE'S ADVENTURES IN WONDERLAND

        Contents

         CHAPTER I.     Down the Rabbit-Hole
         CHAPTER II.    The Pool of Tears
         CHAPTER III.   A Caucus-Race and a Long Tale

        CHAPTER I.
        Down the Rabbit-Hole

        $body

        So she was considering in her own mind, as well as she could, whether the pleasure would be worth the trouble.

        CHAPTER II.
        The Pool of Tears

        “Curiouser and curiouser!” cried Alice; she was so much surprised that for the moment she quite forgot how to speak.

        CHAPTER III.
        A Caucus-Race and a Long Tale

        They were indeed a queer-looking party that assembled on the bank, the birds with draggled feathers.

        *** END OF THE PROJECT GUTENBERG EBOOK ALICE'S ADVENTURES IN WONDERLAND ***

        Section 1. General Terms of Use and Redistributing Project Gutenberg electronic works.
    """.trimIndent()

    @Test
    fun vorspannInhaltUndKapitel() {
        val book = TxtParser.parseText(pg)
        assertEquals("Alice's Adventures in Wonderland", book.title)
        assertEquals("Lewis Carroll", book.author)
        val titles = book.chapters.map { it.title }
        assertEquals(
            listOf("CHAPTER I. Down the Rabbit-Hole", "CHAPTER II. The Pool of Tears", "CHAPTER III. A Caucus-Race and a Long Tale"),
            titles.drop(1),
        )
        val all = book.chapters.flatMap { c -> c.paragraphs.flatMap { p -> p.sentences.map { it.text } } }.joinToString(" ")
        assertFalse(all, "Gutenberg" in all)
        assertFalse(all, "Produced by" in all)
        assertFalse(all, "Contents" in all)
        assertTrue(all, body in all)
        // Vor dem ersten Kapitel bleibt nur die Titelseite
        assertEquals("ALICE'S ADVENTURES IN WONDERLAND", book.chapters[0].paragraphs.single().sentences.single().text)
    }

    @Test
    fun ohneVorspannBleibtAlles() {
        val text = "Erster Absatz mit genug Text, damit er ein Satz ist.\n\nZweiter Absatz, ebenfalls mit etwas Text."
        assertEquals(text, TxtParser.stripGutenberg(text))
        assertEquals(null to null, TxtParser.gutenbergHeader(text))
    }

    @Test
    fun roemischeZiffernUndGrossbuchstaben() {
        val text = listOf(
            "I.", "Es war einmal ein König, der hatte drei Töchter und ein großes Schloss am Meer.",
            "II.", "Die jüngste Tochter ging jeden Morgen hinunter an den Strand und sammelte Muscheln.",
            "III.", "Eines Tages fand sie dort einen goldenen Ring zwischen den Steinen.",
        ).joinToString("\n\n")
        assertEquals(listOf("I.", "II.", "III."), TxtParser.parseText(text, "Märchen").chapters.map { it.title })

        val caps = listOf(
            "DER AUFBRUCH", "Am Morgen packten sie ihre Sachen und gingen los, ohne sich umzusehen.",
            "DER WALD", "Im Wald war es still, nur ein Specht klopfte irgendwo in der Ferne.",
            "DIE HÜTTE", "Am Abend fanden sie eine verlassene Hütte und blieben dort über Nacht.",
        ).joinToString("\n\n")
        assertEquals(listOf("DER AUFBRUCH", "DER WALD", "DIE HÜTTE"), TxtParser.parseText(caps, "Reise").chapters.map { it.title })
    }

    @Test
    fun einzelneZifferIstKeinKapitel() {
        val text = "Ich zählte bis drei.\n\n3\n\nDann war es still im Raum, und niemand sagte mehr etwas."
        assertEquals(1, TxtParser.parseText(text, "Kurz").chapters.size)
    }

    @Test
    fun inhaltsverzeichnisMitPunktlinien() {
        val blocks = listOf(
            "Inhalt",
            "Der Aufbruch . . . . . 5\nDer Wald . . . . . 12\nDie Hütte . . . . . 27",
            "Der Aufbruch",
            "Am Morgen packten sie ihre Sachen und gingen los, ohne sich noch einmal umzusehen, denn der Weg war weit.",
        )
        assertEquals(blocks.drop(2), TxtParser.removePrintedToc(blocks))
        // Ohne Überschrift "Inhalt" bleibt alles
        assertEquals(blocks.drop(1), TxtParser.removePrintedToc(blocks.drop(1)))
        // Zwei Einträge nur, wenn das Buch einen davon wiederholt
        val two = listOf("Contents", "CHAPTER I. The Door\nCHAPTER II. The Night", "CHAPTER I.\nThe Door", "It was dark.")
        assertEquals(two.drop(2), TxtParser.removePrintedToc(two))
        val unconfirmed = listOf("Contents", "CHAPTER I. The Door\nCHAPTER II. The Night", "It was dark and the door was shut, so nobody could come in or go out.")
        assertEquals(unconfirmed, TxtParser.removePrintedToc(unconfirmed))
    }
}
