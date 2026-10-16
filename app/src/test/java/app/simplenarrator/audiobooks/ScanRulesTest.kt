package app.simplenarrator.audiobooks

import app.simplenarrator.audiobooks.data.Mark
import app.simplenarrator.audiobooks.data.Track
import app.simplenarrator.audiobooks.scan.BookBuilder
import app.simplenarrator.audiobooks.scan.Candidate
import app.simplenarrator.audiobooks.scan.FsDir
import app.simplenarrator.audiobooks.scan.FsFile
import app.simplenarrator.audiobooks.scan.Grouping
import app.simplenarrator.audiobooks.scan.Names
import app.simplenarrator.audiobooks.scan.NaturalOrder
import app.simplenarrator.audiobooks.scan.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulesTest {

    private var n = 0
    private fun f(name: String, size: Long = 1000) = FsFile("id${n++}", name, size)
    private fun d(name: String, vararg content: Any): FsDir = FsDir(
        "d${n++}", name,
        content.filterIsInstance<FsFile>(),
        content.filterIsInstance<FsDir>(),
    )

    @Test
    fun natural_order() {
        val list = listOf("Teil 10.mp3", "Teil 2.mp3", "teil 1.mp3", "CD2/01.mp3", "CD1/10.mp3", "CD1/02.mp3")
        assertEquals(
            listOf("CD1/02.mp3", "CD1/10.mp3", "CD2/01.mp3", "teil 1.mp3", "Teil 2.mp3", "Teil 10.mp3"),
            list.sortedWith(NaturalOrder),
        )
        assertTrue(NaturalOrder.compare("001.mp3", "01.mp3") != 0)
        assertTrue(NaturalOrder.compare("a/b.mp3", "a b.mp3") < 0)
    }

    @Test
    fun file_name_titles() {
        assertEquals("Kapitel 1", Names.titleFromFileName("01 - Kapitel 1.mp3"))
        assertEquals("Der Anfang", Names.titleFromFileName("003_Der_Anfang.m4a"))
        assertNull(Names.titleFromFileName("001.mp3"))
        assertEquals("Track 01", Names.titleFromFileName("Track 01.mp3"))
    }

    @Test
    fun disc_suffix_and_folders() {
        assertEquals("Der Hobbit", Names.stripDiscSuffix("Der Hobbit (CD 3)"))
        assertEquals("Der Hobbit", Names.stripDiscSuffix("Der Hobbit - Disc 2"))
        assertEquals("Der Hobbit", Names.stripDiscSuffix("Der Hobbit [CD 3/8]"))
        assertEquals("Teil 3", Names.stripDiscSuffix("Teil 3"))
        assertTrue(Names.isDiscFolder("CD 1"))
        assertTrue(Names.isDiscFolder("Disc2"))
        assertTrue(Names.isDiscFolder("CD3 - Der Weg"))
        assertTrue(Names.isDiscFolder("01"))
        assertFalse(Names.isDiscFolder("Band 1"))
        assertFalse(Names.isDiscFolder("Harry Potter 1"))
    }

    @Test
    fun plausible_years() {
        assertEquals("2002", Names.plausibleYear("2002"))
        assertEquals("2019", Names.plausibleYear("2019-04-01T00:00:00Z"))
        assertNull(Names.plausibleYear("1904"))
        assertNull(Names.plausibleYear("1970"))
        assertNull(Names.plausibleYear("19040101T000000.000Z"))
        assertNull(Names.plausibleYear("abc"))
        assertNull(Names.plausibleYear("3024"))
    }

    @Test
    fun author_title_split() {
        assertEquals("Thomas Mann" to "Buddenbrooks", Names.splitAuthorTitle("Thomas Mann - Buddenbrooks"))
        assertNull(Names.splitAuthorTitle("Buddenbrooks"))
        assertNull(Names.splitAuthorTitle("01 - Buddenbrooks"))
    }

    @Test
    fun grouping_collection() {
        val root = d(
            "Hörbücher",
            f("Einzeln.m4b"),
            f("liesmich.txt"),
            d("Buddenbrooks", f("01.mp3"), f("02.mp3"), f("cover.jpg")),
            d("Stephen King", d("Es", f("Es 1.mp3"), f("Es 2.mp3")), d("Carrie", f("Carrie.m4b"))),
            d("Der Hobbit", d("CD 1", f("01.mp3"), f("02.mp3")), d("CD 2", f("01.mp3"))),
            d("Sammlung", f("Buch A.m4b"), f("Buch B.m4b")),
            d("Leer", f("notiz.txt")),
        )
        val books = Grouping.group(root, rootIsBook = false).associateBy { it.path }
        assertEquals(
            setOf("Einzeln.m4b", "Buddenbrooks", "Stephen King/Es", "Stephen King/Carrie", "Der Hobbit", "Sammlung/Buch A.m4b", "Sammlung/Buch B.m4b"),
            books.keys,
        )
        assertTrue(books["Einzeln.m4b"]!!.singleFile)
        assertEquals("Stephen King", books["Stephen King/Es"]!!.parentName)
        assertNull(books["Buddenbrooks"]!!.parentName)
        assertEquals(listOf("CD 1/01.mp3", "CD 1/02.mp3", "CD 2/01.mp3"), books["Der Hobbit"]!!.audio.map { it.path })
        assertEquals(1, books["Buddenbrooks"]!!.images.size)
        assertEquals("Sammlung", books["Sammlung/Buch A.m4b"]!!.parentName)
    }

    @Test
    fun grouping_single_book_root() {
        val root = d("Mein Buch", f("02.mp3"), f("01.mp3"), d("Bonus", f("extra.mp3")))
        val books = Grouping.group(root, rootIsBook = true)
        assertEquals(1, books.size)
        assertEquals("", books[0].path)
        assertEquals(listOf("01.mp3", "02.mp3", "Bonus/extra.mp3"), books[0].audio.map { it.path })
    }

    @Test
    fun cover_image_choice() {
        val imgs = listOf(f("back.jpg", 900_000), f("Folder.jpg", 20_000), f("scan.png", 50_000))
        assertEquals("Folder.jpg", Grouping.pickCoverImage(imgs)!!.name)
        assertEquals("scan.png", Grouping.pickCoverImage(listOf(f("scan.png", 50_000), f("x.jpg", 10)))!!.name)
        assertEquals("inlay.jpg", Grouping.pickCoverImage(listOf(f("Back.jpg", 900_000), f("inlay.jpg", 10)))!!.name)
    }

    private fun cand(name: String, parent: String? = null, single: Boolean = false) =
        Candidate(name, name, emptyList(), emptyList(), emptyList(), parent, single)

    @Test
    fun meta_from_tags_and_names() {
        val tags = listOf(
            Tags(album = "Der Hobbit (CD 1)", albumArtist = "J. R. R. Tolkien", composer = "Gert Heidenreich", year = "2002"),
            Tags(album = "Der Hobbit (CD 2)", albumArtist = "J. R. R. Tolkien", composer = "Gert Heidenreich"),
        )
        val m = BookBuilder.meta(cand("hobbit"), tags)
        assertEquals("Der Hobbit", m.title)
        assertEquals("J. R. R. Tolkien", m.author)
        assertEquals("Gert Heidenreich", m.narrator)
        assertEquals("2002", m.year)

        val noTags = BookBuilder.meta(cand("Thomas Mann - Buddenbrooks"), listOf(Tags()))
        assertEquals("Buddenbrooks", noTags.title)
        assertEquals("Thomas Mann", noTags.author)

        val nested = BookBuilder.meta(cand("Es", parent = "Stephen King"), listOf(Tags()))
        assertEquals("Es", nested.title)
        assertEquals("Stephen King", nested.author)

        val authorInAlbum = BookBuilder.meta(cand("x"), listOf(Tags(album = "Thomas Mann - Buddenbrooks", artist = "Thomas Mann")))
        assertEquals("Buddenbrooks", authorInAlbum.title)
    }

    private fun t(path: String, dur: Long, title: String? = null, marks: List<Mark> = emptyList()) =
        Track("content://$path", path, dur, title = title, marks = marks)

    @Test
    fun chapters_from_files_and_marks() {
        val tracks = listOf(
            t("01 - Prolog.mp3", 60_000),
            t("02.m4b", 120_000, marks = listOf(Mark("A", 0), Mark("B", 50_000))),
            t("03 - Epilog.mp3", 30_000),
        )
        val ch = BookBuilder.chapters(tracks)
        assertEquals(listOf("Prolog", "A", "B", "Epilog"), ch.map { it.title })
        assertEquals(listOf(0L, 60_000L, 110_000L, 180_000L), ch.map { it.startMs })
        assertEquals(listOf(60_000L, 110_000L, 180_000L, 210_000L), ch.map { it.endMs })
        assertEquals(listOf(0, 1, 1, 2), ch.map { it.track })
    }

    @Test
    fun leading_part_before_first_mark_becomes_chapter() {
        val ch = BookBuilder.chapters(listOf(t("Buch.m4b", 100_000, title = "Buch", marks = listOf(Mark("Eins", 5_000), Mark("Zwei", 40_000)))))
        assertEquals(listOf("Buch", "Eins", "Zwei"), ch.map { it.title })
        assertEquals(listOf(0L, 5_000L, 40_000L), ch.map { it.startMs })
    }

    @Test
    fun tag_titles_only_when_distinct() {
        val same = listOf(t("01.mp3", 1, "Hörbuch"), t("02.mp3", 1, "Hörbuch"), t("03.mp3", 1, "Hörbuch"))
        assertFalse(BookBuilder.useTagTitles(same))
        val distinct = listOf(t("01.mp3", 1, "Eins"), t("02.mp3", 1, "Zwei"), t("03.mp3", 1, "Drei"))
        assertTrue(BookBuilder.useTagTitles(distinct))
        assertEquals(listOf(null, null, null), BookBuilder.chapters(same).map { it.title })
    }
}
