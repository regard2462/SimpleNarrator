package app.simplenarrator.export

import app.simplenarrator.export.Mp4TestKit.find
import app.simplenarrator.export.Mp4TestKit.ilst
import app.simplenarrator.export.Mp4TestKit.text
import app.simplenarrator.export.Mp4TestKit.trackSamples
import app.simplenarrator.testutil.sa.ArraySource
import app.simplenarrator.testutil.sa.Mp4Chapters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Kapitel und Metadaten in MP4: Nero-Liste, QuickTime-Spur, ilst, Offsets bei moov vor und hinter mdat. */
class Mp4TagsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 9, 9)
    private val meta = AudioMeta("Der Testbuch-Titel", "Erika Muster", jpeg, "image/jpeg")
    private val chapters = listOf(
        ChapterMark("Anfang", 0, 12_000),
        ChapterMark("Mitte mit Ümlaut & <Zeichen>", 12_000, 40_500),
        ChapterMark("Ende", 40_500, 60_000),
    )

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { writeBytes(bytes) }

    private fun fixture(name: String): File =
        File(tmp.root, name).apply { writeBytes(Mp4TagsTest::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { it.readBytes() }) }

    private fun read(bytes: ByteArray) = Mp4Chapters.read(ArraySource(bytes))!!

    /** Macht den tref/chap-Verweis unkenntlich, damit der Leser auf die Nero-Liste zurückfällt. */
    private fun withoutChapterTrack(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val s = String(copy, Charsets.ISO_8859_1)
        val tref = s.indexOf("tref")
        assertTrue("tref present", tref > 0)
        val chap = s.indexOf("chap", tref)
        "free".toByteArray().copyInto(copy, chap)
        return copy
    }

    private fun assertChapters(bytes: ByteArray, expectedStarts: List<Long> = listOf(0, 12_000, 40_500)) {
        val quick = read(bytes)
        assertEquals(chapters.map { it.title }, quick.marks.map { it.title })
        assertEquals(expectedStarts, quick.marks.map { it.startMs })
        val nero = read(withoutChapterTrack(bytes))
        assertEquals(chapters.map { it.title }, nero.marks.map { it.title })
        assertEquals(expectedStarts, nero.marks.map { it.startMs })
    }

    private fun assertMeta(bytes: ByteArray) {
        assertEquals("Der Testbuch-Titel", text(bytes, "©nam"))
        assertEquals("Erika Muster", text(bytes, "©ART"))
        assertEquals("Erika Muster", text(bytes, "aART"))
        assertEquals("Der Testbuch-Titel", text(bytes, "©alb"))
        assertEquals("Audiobook", text(bytes, "©gen"))
        val items = ilst(bytes)
        assertEquals(21, items.getValue("stik").first)
        assertArrayEquals(byteArrayOf(2), items.getValue("stik").second)
        assertEquals(13, items.getValue("covr").first)
        assertArrayEquals(jpeg, items.getValue("covr").second)
        // meta ist eine FullBox mit hdlr mdir
        val metaBox = find(bytes, "moov/udta/meta")!!
        assertEquals(0L, Mp4TestKit.be(bytes, metaBox.payload, 4))
        val hdlr = find(bytes, "moov/udta/meta/hdlr")!!
        assertEquals("mdir", String(bytes, hdlr.payload + 8, 4, Charsets.ISO_8859_1))
        assertEquals("one udta", 1, Mp4TestKit.all(bytes, find(bytes, "moov")!!, "udta").size)
    }

    private fun assertSamples(before: Mp4TestKit.Synthetic, after: ByteArray) {
        val audio = trackSamples(after).single { it.handler == "soun" }
        assertEquals(before.samples.size, audio.samples.size)
        before.samples.indices.forEach { assertArrayEquals("sample $it", before.samples[it], audio.samples[it]) }
    }

    @Test
    fun moovLast() {
        val syn = Mp4TestKit.synthetic(moovFirst = false)
        val f = file("last.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertMeta(out)
        assertSamples(syn, out)
        // Daten vor moov liegen unverändert an derselben Stelle
        val oldMoov = Mp4TestKit.top(syn.bytes).first { it.type == "moov" }
        assertArrayEquals(syn.bytes.copyOf(oldMoov.start), out.copyOf(oldMoov.start))
        assertEquals(60_000L, read(out).durationMs)
        assertFalse(File(tmp.root, "last.m4a.part").exists())
    }

    @Test
    fun moovFirstPatchesStco() {
        val syn = Mp4TestKit.synthetic(moovFirst = true)
        val f = file("first.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertMeta(out)
        assertSamples(syn, out)
        val oldMoov = Mp4TestKit.top(syn.bytes).first { it.type == "moov" }
        val newMoov = Mp4TestKit.top(out).first { it.type == "moov" }
        val delta = (newMoov.size - oldMoov.size).toLong()
        assertTrue(delta > 0)
        val before = trackSamples(syn.bytes).single().offsets
        val after = trackSamples(out).single { it.handler == "soun" }.offsets
        assertEquals(before.map { it + delta }, after)
    }

    @Test
    fun moovFirstPatchesCo64AndLargeMdat() {
        val syn = Mp4TestKit.synthetic(moovFirst = true, co64 = true, largeMdat = true)
        val f = file("co64.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertSamples(syn, out)
        assertNotNull(find(out, "moov/trak/mdia/minf/stbl/co64"))
        val mdat = Mp4TestKit.top(out).first { it.type == "mdat" }
        assertEquals(16, mdat.header)
    }

    @Test
    fun moovLastWithLargeMdat() {
        val syn = Mp4TestKit.synthetic(moovFirst = false, largeMdat = true)
        val f = file("large.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertSamples(syn, out)
    }

    @Test
    fun moovFirstUsesFollowingFreeBox() {
        val syn = Mp4TestKit.synthetic(moovFirst = true, freeAfterMoov = 4096)
        val f = file("free.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertSamples(syn, out)
        // Offsets und mdat-Lage unverändert, free-Box ist geschrumpft
        assertEquals(trackSamples(syn.bytes).single().offsets, trackSamples(out).single { it.handler == "soun" }.offsets)
        val oldMdat = Mp4TestKit.top(syn.bytes).first { it.type == "mdat" }
        val newMdat = Mp4TestKit.top(out).first { it.type == "mdat" }
        assertEquals(oldMdat.start, newMdat.start)
        val free = Mp4TestKit.top(out).first { it.type == "free" }
        assertTrue(free.size < 4096)
    }

    @Test
    fun moovFirstWithTooSmallFreeBoxShifts() {
        val syn = Mp4TestKit.synthetic(moovFirst = true, freeAfterMoov = 16)
        val f = file("smallfree.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertSamples(syn, out)
    }

    @Test
    fun shrinkingMoovBeforeMdatKeepsOffsets() {
        // Großer alter udta-Block (Cover) wird durch einen kleineren ersetzt: Lücke wird zur free-Box
        val bigCover = ByteArray(5000) { 1 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val oldUdta = Mp4TestKit.box("udta", Mp4TestKit.full("meta", 0, 0,
            Mp4TestKit.full("hdlr", 0, 0, Mp4TestKit.u32(0), "mdir".toByteArray(), "appl".toByteArray(), ByteArray(9)),
            Mp4TestKit.box("ilst", Mp4TestKit.box("covr", Mp4TestKit.box("data", Mp4TestKit.u32(13), Mp4TestKit.u32(0), bigCover)),
                Mp4TestKit.box("©too", Mp4TestKit.box("data", Mp4TestKit.u32(1), Mp4TestKit.u32(0), "Encoder 1".toByteArray()))),
        ))
        val syn = Mp4TestKit.synthetic(moovFirst = true, udta = oldUdta)
        val f = file("shrink.m4a", syn.bytes)
        Mp4Tags.write(f, AudioMeta("T", "A", jpeg), chapters, chapterTrack = false)
        val out = f.readBytes()
        assertSamples(syn, out)
        assertEquals(trackSamples(syn.bytes).single().offsets, trackSamples(out).single().offsets)
        assertEquals("Encoder 1", text(out, "©too"))
        assertArrayEquals(jpeg, ilst(out).getValue("covr").second)
        assertTrue(Mp4TestKit.top(out).any { it.type == "free" })
        assertEquals(syn.bytes.size, out.size)
    }

    @Test
    fun overflowFailsAndKeepsFile() {
        val syn = Mp4TestKit.synthetic(moovFirst = true, bogusOffset = 0xFFFFFFF0L)
        val f = file("overflow.m4a", syn.bytes)
        try {
            Mp4Tags.write(f, meta, chapters)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("32 bits"))
        }
        assertArrayEquals(syn.bytes, f.readBytes())
        assertFalse(File(tmp.root, "overflow.m4a.part").exists())
    }

    @Test
    fun writingTwiceReplacesEverything() {
        val syn = Mp4TestKit.synthetic(moovFirst = false)
        val f = file("twice.m4a", syn.bytes)
        Mp4Tags.write(f, AudioMeta("Alt", "Alter Autor"), listOf(ChapterMark("Alt 1", 0, 5000), ChapterMark("Alt 2", 5000, 60_000)))
        val firstLen = f.length()
        Mp4Tags.write(f, AudioMeta("Alt", "Alter Autor"), listOf(ChapterMark("Alt 1", 0, 5000), ChapterMark("Alt 2", 5000, 60_000)))
        assertEquals("old chapter mdat is dropped", firstLen, f.length())
        Mp4Tags.write(f, meta, chapters)
        val out = f.readBytes()
        assertChapters(out)
        assertMeta(out)
        assertSamples(syn, out)
        val moov = find(out, "moov")!!
        assertEquals(2, Mp4TestKit.all(out, moov, "trak").size)
        assertEquals(1, Mp4TestKit.all(out, find(out, "moov/udta")!!, "chpl").size)
        assertEquals(1, Mp4TestKit.all(out, find(out, "moov/udta")!!, "meta").size)
        // next_track_ID im mvhd zeigt hinter die Kapitelspur
        val mvhd = find(out, "moov/mvhd")!!
        assertEquals(3L, Mp4TestKit.be(out, mvhd.payload + 96, 4))
    }

    @Test
    fun neroOnly() {
        val syn = Mp4TestKit.synthetic(moovFirst = false)
        val f = file("nero.m4a", syn.bytes)
        Mp4Tags.write(f, meta, chapters, chapterTrack = false)
        val out = f.readBytes()
        assertEquals(1, Mp4TestKit.all(out, find(out, "moov")!!, "trak").size)
        val r = read(out)
        assertEquals(chapters.map { it.title }, r.marks.map { it.title })
        assertEquals(listOf(0L, 12_000L, 40_500L), r.marks.map { it.startMs })
        assertNull(find(out, "moov/trak/tref"))
        assertSamples(syn, out)
    }

    @Test
    fun separateAlbumForChapterFiles() {
        val syn = Mp4TestKit.synthetic(moovFirst = true, freeAfterMoov = 2048)
        val f = file("chapter.m4a", syn.bytes)
        Mp4Tags.write(f, AudioMeta("01 Anfang", "Erika Muster", album = "Das Buch"), listOf(ChapterMark("Anfang", 0, 60_000)))
        val out = f.readBytes()
        assertEquals("01 Anfang", text(out, "©nam"))
        assertEquals("Das Buch", text(out, "©alb"))
        assertNull(ilst(out)["covr"])
        assertEquals(listOf("Anfang"), read(out).marks.map { it.title })
        assertSamples(syn, out)
    }

    @Test
    fun manyChaptersAndPngCover() {
        val syn = Mp4TestKit.synthetic(moovFirst = true)
        val f = file("many.m4a", syn.bytes)
        val many = (0 until 300).map { ChapterMark("Kapitel ${it + 1}", it * 200L, (it + 1) * 200L) }
        Mp4Tags.write(f, AudioMeta("Viele", "Autor", png, null), many)
        val out = f.readBytes()
        val quick = read(out)
        assertEquals(300, quick.marks.size)
        assertEquals("Kapitel 300", quick.marks.last().title)
        assertEquals(59_800L, quick.marks.last().startMs)
        val nero = read(withoutChapterTrack(out))
        assertEquals(255, nero.marks.size)
        assertEquals(14, ilst(out).getValue("covr").first)
        assertSamples(syn, out)
    }

    @Test
    fun firstChapterNotAtZero() {
        val syn = Mp4TestKit.synthetic(moovFirst = false)
        val f = file("late.m4a", syn.bytes)
        Mp4Tags.write(f, meta, listOf(ChapterMark("A", 3000, 10_000), ChapterMark("B", 10_000, 70_000), ChapterMark("hinter dem Ende", 61_000, 62_000)))
        val out = f.readBytes()
        // QuickTime-Spur beginnt bei 0, Nero behält die echte Startzeit; Marken hinter dem Dateiende fallen weg
        assertEquals(listOf(0L, 10_000L), read(out).marks.map { it.startMs })
        assertEquals(listOf(3000L, 10_000L), read(withoutChapterTrack(out)).marks.map { it.startMs })
    }

    @Test
    fun neroFixture() {
        val f = fixture("nero.m4b")
        val before = trackSamples(f.readBytes()).single()
        Mp4Tags.write(f, meta, listOf(ChapterMark("Anfang", 0, 4000), ChapterMark("Mitte mit Ümlaut & <Zeichen>", 4000, 9500), ChapterMark("Ende", 9500, 12_000)))
        val out = f.readBytes()
        val r = read(out)
        assertEquals(listOf("Anfang", "Mitte mit Ümlaut & <Zeichen>", "Ende"), r.marks.map { it.title })
        assertEquals(listOf(0L, 4000L, 9500L), r.marks.map { it.startMs })
        assertEquals(listOf(0L, 4000L, 9500L), read(withoutChapterTrack(out)).marks.map { it.startMs })
        assertMeta(out)
        assertEquals("Lavf61.7.100", text(out, "©too"))   // fremde Einträge bleiben
        assertEquals("Max Sprecher", text(out, "©wrt"))
        val after = trackSamples(out).first { it.handler == "soun" }
        assertEquals(before.samples.size, after.samples.size)
        before.samples.indices.forEach { assertArrayEquals(before.samples[it], after.samples[it]) }
    }

    @Test
    fun quicktimeFixtureReplacesChapterTrack() {
        val f = fixture("quicktime.m4b")
        val before = trackSamples(f.readBytes()).first { it.handler == "soun" }
        Mp4Tags.write(f, meta, listOf(ChapterMark("Eins", 0, 6000), ChapterMark("Zwei", 6000, 12_000)))
        val out = f.readBytes()
        val traks = trackSamples(out)
        assertEquals(listOf("soun", "text"), traks.map { it.handler })
        assertEquals(listOf("Eins", "Zwei"), read(out).marks.map { it.title })
        assertEquals(listOf(0L, 6000L), read(out).marks.map { it.startMs })
        assertEquals(listOf("Eins", "Zwei"), read(withoutChapterTrack(out)).marks.map { it.title })
        val after = traks.first { it.handler == "soun" }
        before.samples.indices.forEach { assertArrayEquals(before.samples[it], after.samples[it]) }
        // Kapitelspur hat eine neue ID, tref zeigt darauf
        val chap = find(out, "moov/trak/tref/chap")!!
        val textTrak = Mp4TestKit.all(out, find(out, "moov")!!, "trak")[1]
        val tkhd = Mp4TestKit.all(out, textTrak, "tkhd").single()
        assertEquals(Mp4TestKit.be(out, chap.payload, 4), Mp4TestKit.be(out, tkhd.payload + 12, 4))
    }

    @Test
    fun emptyChapterListRemovesChapters() {
        val f = fixture("quicktime.m4b")
        Mp4Tags.write(f, meta, emptyList())
        val out = f.readBytes()
        assertTrue(read(out).marks.isEmpty())
        assertNull(find(out, "moov/udta/chpl"))
        assertNull(find(out, "moov/trak/tref"))
        assertEquals(listOf("soun"), trackSamples(out).map { it.handler })
        assertMeta(out)
    }

    @Test
    fun notAnMp4() {
        val f = file("x.m4a", ByteArray(100) { 7 })
        try {
            Mp4Tags.write(f, meta, chapters); fail()
        } catch (e: IOException) {
            // erwartet
        }
        assertArrayEquals(ByteArray(100) { 7 }, f.readBytes())
    }
}
