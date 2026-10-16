package app.simplenarrator.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Einordnung geteilter Inhalte ohne Android-Klassen. */
class IncomingShareTest {
    private fun text(t: String?, subject: String? = null) = IncomingShare.classify(t, subject, null, null)

    @Test
    fun filesWinOverText() {
        assertEquals(Incoming.FileUri("content://media/doc/12"), IncomingShare.classify("Notiz", "Betreff", "content://media/doc/12", null))
        assertEquals(Incoming.FileUri("content://downloads/book.epub"), IncomingShare.classify(null, null, null, "content://downloads/book.epub"))
        assertEquals(Incoming.FileUri("file:///sdcard/a.txt"), IncomingShare.classify(null, null, " ", "file:///sdcard/a.txt"))
    }

    @Test
    fun viewWithWebAddress() {
        assertEquals(Incoming.Url("https://example.com/a", null), IncomingShare.classify(null, null, null, "https://example.com/a"))
    }

    @Test
    fun onlyUrl() {
        assertEquals(Incoming.Url("https://www.example.com/news/artikel-123", null), text("  https://www.example.com/news/artikel-123\n"))
        assertEquals(Incoming.Url("https://example.com/x", "Seitentitel"), text("https://example.com/x", subject = " Seitentitel "))
    }

    @Test
    fun urlWithShortTitleAsBrowsersShareIt() {
        assertEquals(
            Incoming.Url("https://example.com/story", "Stadtrat beschließt neues Radwegenetz"),
            text("Stadtrat beschließt neues Radwegenetz\nhttps://example.com/story"),
        )
        assertEquals(
            Incoming.Url("https://example.com/story", "Great read"),
            text("Great read - https://example.com/story."),
        )
        // Betreff aus EXTRA_SUBJECT hat Vorrang
        assertEquals(
            Incoming.Url("https://example.com/story", "Offizieller Titel"),
            text("Schau mal: https://example.com/story", subject = "Offizieller Titel"),
        )
    }

    @Test
    fun wikipediaParenthesesStay() {
        assertEquals(Incoming.Url("https://de.wikipedia.org/wiki/Faust_(Goethe)", null), text("https://de.wikipedia.org/wiki/Faust_(Goethe)"))
        assertEquals(Incoming.Url("https://example.com/a", "siehe"), text("(siehe https://example.com/a)"))
    }

    @Test
    fun longTextWithUrlStaysText() {
        val long = "Es war einmal ein König, der hatte eine Tochter, und die war sehr schön. ".repeat(5) +
            "Mehr dazu unter https://example.com/maerchen und in jedem Buchladen."
        assertEquals(Incoming.Text(long, null), text(long))
    }

    @Test
    fun shortTextWithTwoUrlsStaysText() {
        val t = "Vergleich: https://a.example/x und https://b.example/y"
        assertEquals(Incoming.Text(t, "Links"), text(t, subject = "Links"))
    }

    @Test
    fun bareDomain() {
        assertEquals(Incoming.Url("https://www.example.com/artikel", null), text("www.example.com/artikel"))
    }

    @Test
    fun plainTextAndNothing() {
        assertEquals(Incoming.Text("Hallo Welt", null), text("Hallo Welt"))
        assertNull(text(null))
        assertNull(text("   "))
        assertNull(IncomingShare.classify(null, "nur Betreff", null, null))
    }

    @Test
    fun urlFromClipboard() {
        assertEquals("https://example.com/a", IncomingShare.urlFrom("https://example.com/a"))
        assertNull(IncomingShare.urlFrom("Ein normaler Satz ohne Adresse."))
        assertNull(IncomingShare.urlFrom(null))
    }
}
