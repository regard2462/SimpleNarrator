package app.simplenarrator.audiobooks

import app.simplenarrator.audiobooks.online.CoverCandidate
import app.simplenarrator.audiobooks.online.CoverMatch
import app.simplenarrator.audiobooks.online.CoverSources
import app.simplenarrator.audiobooks.online.Market
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineCoversTest {

    private fun c(title: String, vararg authors: String) = CoverCandidate("Test", title, authors.toList(), "https://x/$title.jpg", "https://x/$title-s.jpg")

    @Test
    fun normalizesUmlautsAndAccents() {
        assertEquals("die hoelle von bjoern", CoverMatch.normalize("Die Hölle von Björn"))
        assertEquals("strasse", CoverMatch.normalize("Straße"))
        assertEquals("cafe", CoverMatch.normalize("Café"))
        assertEquals(listOf("hoelle", "1"), CoverMatch.words("Hölle 01 (ungekürzt) Hörbuch"))
    }

    @Test
    fun titleSimilarityIgnoresEditionNoise() {
        assertEquals(1f, CoverMatch.titleSimilarity("Der Hobbit", "Der Hobbit (Ungekürzte Lesung)"), 0.001f)
        assertEquals(1f, CoverMatch.titleSimilarity("Der Hobbit", "Der Hobbit: oder Hin und zurück"), 0.001f)
        assertTrue(CoverMatch.titleSimilarity("Harry Potter 1 - Der Stein der Weisen", "Harry Potter und der Stein der Weisen") >= 0.8f)
        assertTrue(CoverMatch.titleSimilarity("Der Stein der Weisen", "Der Stein") < 0.75f)
        assertTrue(CoverMatch.titleSimilarity("Die lange Reise", "Die Reise zum Mittelpunkt der Erde") < 0.5f)
    }

    @Test
    fun authorMatchesBySurname() {
        assertTrue(CoverMatch.authorMatches("Tolkien", listOf("J. R. R. Tolkien")))
        assertTrue(CoverMatch.authorMatches("J.R.R. Tolkien", listOf("Tolkien, J. R. R.")))
        assertTrue(CoverMatch.authorMatches("Rowling, J.K.", listOf("Joanne K. Rowling")))
        assertFalse(CoverMatch.authorMatches("Stephen King", listOf("Sebastian Fitzek")))
        assertFalse(CoverMatch.authorMatches("J. K.", listOf("J. K. Rowling")))
    }

    @Test
    fun acceptsOnlyClearMatches() {
        assertTrue(CoverMatch.accepts("Der Hobbit", "J. R. R. Tolkien", c("Der Hobbit", "J. R. R. Tolkien")))
        // gleicher Titel, anderer Autor
        assertFalse(CoverMatch.accepts("Der Kater", "Anna Beispiel", c("Der Kater", "Patrick Süskind")))
        // ohne Autor nur bei langem, gleichem Titel
        assertFalse(CoverMatch.accepts("Der Kater", null, c("Der Kater", "Jemand")))
        assertTrue(CoverMatch.accepts("Die unendliche Geschichte", null, c("Die unendliche Geschichte", "Michael Ende")))
        assertFalse(CoverMatch.accepts("Die unendliche Geschichte", null, c("Die unendliche Geschichte 2", "Michael Ende")))
        assertFalse(CoverMatch.accepts("Die unendliche Geschichte", null, c("Die unendliche Geschichte")))
    }

    @Test
    fun withoutAuthorAllTitleMatchesMustAgree() {
        val ende = listOf(
            c("Die unendliche Geschichte"),
            c("Die unendliche Geschichte", "Michael Ende"),
            c("Die unendliche Geschichte: Das Hörspiel", "Michael Ende", "Anna Thalbach"),
        )
        assertEquals(listOf("Michael Ende"), CoverMatch.best("Die unendliche Geschichte", null, ende)?.authors)
        val mixed = listOf(c("Die lange dunkle Reise", "Anna Beispiel"), c("Die lange dunkle Reise", "Otto Muster"))
        assertNull(CoverMatch.best("Die lange dunkle Reise", null, mixed))
        assertEquals("Anna Beispiel", CoverMatch.best("Die lange dunkle Reise", "Beispiel", mixed)?.authors?.first())
    }

    @Test
    fun bestPrefersHigherTitleSimilarity() {
        val list = listOf(
            c("Der Herr der Ringe: Die Gefährten", "Tolkien"),
            c("Der Hobbit", "J. R. R. Tolkien"),
            c("Der Hobbit", "Andere Person"),
        )
        assertEquals("Der Hobbit", CoverMatch.best("Der Hobbit", "Tolkien", list)?.title)
        assertNull(CoverMatch.best("Der Hobbit", "Fitzek", list))
        assertEquals("Der Hobbit", CoverMatch.rank("Der Hobbit", "Tolkien", list).first().title)
    }

    @Test
    fun parsesAudible() {
        val body = """{"products":[
            {"asin":"A1","title":"Der Hobbit","subtitle":"Ungekürzt","authors":[{"name":"J. R. R. Tolkien"}],
             "product_images":{"500":"https://m.media-amazon.com/images/I/a._SL500_.jpg","1000":"https://m.media-amazon.com/images/I/b._SL1000_.jpg"}},
            {"asin":"A2","title":"Ohne Bild","authors":[]},
            {"asin":"A3","title":"Der Hobbit","content_delivery_type":"PodcastEpisode","content_type":"Podcast",
             "product_images":{"500":"https://m.media-amazon.com/images/I/p.jpg"}}
        ]}"""
        val list = CoverSources.parseAudible(body)
        assertEquals(1, list.size)
        assertEquals("Der Hobbit: Ungekürzt", list[0].title)
        assertEquals(listOf("J. R. R. Tolkien"), list[0].authors)
        assertEquals("https://m.media-amazon.com/images/I/b._SL1000_.jpg", list[0].imageUrl)
        assertEquals("https://m.media-amazon.com/images/I/a._SL500_.jpg", list[0].previewUrl)
        assertTrue(CoverSources.parseAudible("kein json").isEmpty())
    }

    @Test
    fun parsesApple() {
        val body = """{"resultCount":1,"results":[{"wrapperType":"audiobook","artistName":"Max Muster & Erika Beispiel",
            "collectionName":"Ein Titel","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/x.jpg/100x100bb.jpg"}]}"""
        val list = CoverSources.parseApple(body)
        assertEquals(1, list.size)
        assertEquals(listOf("Max Muster", "Erika Beispiel"), list[0].authors)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/x.jpg/1000x1000bb.jpg", list[0].imageUrl)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/x.jpg/300x300bb.jpg", list[0].previewUrl)
    }

    @Test
    fun parsesOpenLibrary() {
        val body = """{"numFound":2,"docs":[{"title":"Momo","author_name":["Michael Ende"],"cover_i":123},{"title":"Ohne Cover"}]}"""
        val list = CoverSources.parseOpenLibrary(body)
        assertEquals(1, list.size)
        assertEquals("https://covers.openlibrary.org/b/id/123-L.jpg", list[0].imageUrl)
        assertEquals(listOf("Michael Ende"), list[0].authors)
    }

    @Test
    fun buildsUrlsWithEncodedWords() {
        val a = CoverSources.audibleUrl(Market.DE, "Der Hobbit", "Tolkien")
        assertTrue(a.startsWith("https://api.audible.de/1.0/catalog/products?"))
        assertTrue(a.contains("&title=Der+Hobbit") && a.contains("&author=Tolkien"))
        assertTrue(CoverSources.appleUrl(Market.US, "Ä & B").contains("country=US") )
        assertTrue(CoverSources.appleUrl(Market.US, "Ä & B").endsWith("term=%C3%84+%26+B"))
        assertEquals(Market.DE, Market.forLanguage("de"))
        assertEquals(Market.US, Market.forLanguage("fr"))
    }
}
