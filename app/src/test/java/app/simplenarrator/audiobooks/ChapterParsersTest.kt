package app.simplenarrator.audiobooks

import app.simplenarrator.audiobooks.scan.chapters.ArraySource
import app.simplenarrator.audiobooks.scan.chapters.CueSheet
import app.simplenarrator.audiobooks.scan.chapters.Id3Chapters
import app.simplenarrator.audiobooks.scan.chapters.Mp4Chapters
import app.simplenarrator.audiobooks.scan.chapters.VorbisChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChapterParsersTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { it.readBytes() }

    private val expectedTitles = listOf("Anfang", "Mitte mit Ümlaut", "Ende")

    @Test
    fun nero_chapters_from_ffmpeg_file() {
        val r = Mp4Chapters.read(ArraySource(fixture("nero.m4b")))!!
        assertEquals(expectedTitles, r.marks.map { it.title })
        assertEquals(listOf(0L, 4000L, 9500L), r.marks.map { it.startMs })
        assertTrue("Dauer ${r.durationMs}", r.durationMs!! in 11_900..12_200)
    }

    @Test
    fun quicktime_chapter_track_from_ffmpeg_file() {
        val r = Mp4Chapters.read(ArraySource(fixture("quicktime.m4b")))!!
        assertEquals(expectedTitles, r.marks.map { it.title })
        assertEquals(listOf(0L, 4000L, 9500L), r.marks.map { it.startMs })
    }

    @Test
    fun quicktime_chapter_track_without_nero_list() {
        val bytes = fixture("quicktime.m4b")
        // chpl unkenntlich machen, damit nur die Kapitelspur bleibt
        val s = String(bytes, Charsets.ISO_8859_1)
        val idx = s.indexOf("chpl")
        assertTrue(idx > 0)
        "free".toByteArray().copyInto(bytes, idx)
        val r = Mp4Chapters.read(ArraySource(bytes))!!
        assertEquals(expectedTitles, r.marks.map { it.title })
        assertEquals(listOf(0L, 4000L, 9500L), r.marks.map { it.startMs })
    }

    @Test
    fun mp4_without_moov_returns_null() {
        assertEquals(null, Mp4Chapters.read(ArraySource(ByteArray(64))))
    }

    @Test
    fun id3v23_chap_frames() {
        val marks = Id3Chapters.read(ArraySource(fixture("chapters23.mp3")))
        assertEquals(expectedTitles, marks.map { it.title })
        assertEquals(listOf(0L, 4000L, 9500L), marks.map { it.startMs })
    }

    @Test
    fun id3v24_chap_frames() {
        val marks = Id3Chapters.read(ArraySource(fixture("chapters24.mp3")))
        assertEquals(expectedTitles, marks.map { it.title })
        assertEquals(listOf(0L, 4000L, 9500L), marks.map { it.startMs })
    }

    @Test
    fun id3_without_tag_is_empty() {
        assertTrue(Id3Chapters.read(ArraySource(ByteArray(100))).isEmpty())
    }

    @Test
    fun opus_vorbis_comment_chapters() {
        val marks = VorbisChapters.readOgg(ArraySource(fixture("chapters.opus")))
        assertEquals(expectedTitles, marks.map { it.title })
        assertEquals(0L, marks[0].startMs)
        assertEquals(4000L, marks[1].startMs)
    }

    @Test
    fun flac_vorbis_comment_block() {
        val comments = listOf("TITLE=Test", "CHAPTER001=00:00:00.000", "CHAPTER001NAME=Eins", "CHAPTER002=00:01:02.500", "CHAPTER002NAME=Zwei")
        val vc = ByteArrayOutputStream()
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        val vendor = "test".toByteArray()
        vc.write(le32(vendor.size)); vc.write(vendor); vc.write(le32(comments.size))
        for (c in comments) { val b = c.toByteArray(); vc.write(le32(b.size)); vc.write(b) }
        val block = vc.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // STREAMINFO (34 Bytes), nicht letzter Block
        out.write(byteArrayOf(0, 0, 0, 34)); out.write(ByteArray(34))
        // VORBIS_COMMENT, letzter Block
        out.write(byteArrayOf((0x80 or 4).toByte(), (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte()))
        out.write(block)
        val marks = VorbisChapters.readFlac(ArraySource(out.toByteArray()))
        assertEquals(listOf("Eins", "Zwei"), marks.map { it.title })
        assertEquals(listOf(0L, 62_500L), marks.map { it.startMs })
    }

    @Test
    fun vorbis_time_formats() {
        assertEquals(3_723_456L, VorbisChapters.parseTime("01:02:03.456"))
        assertEquals(63_500L, VorbisChapters.parseTime("01:03.5"))
        assertEquals(3_600_000L, VorbisChapters.parseTime("1:00:00"))
        assertEquals(null, VorbisChapters.parseTime("abc"))
    }

    @Test
    fun cue_sheet() {
        val cue = """
            REM GENRE Hörbuch
            PERFORMER "Erika Muster"
            TITLE "Testbuch"
            FILE "Buch Teil 1.mp3" MP3
              TRACK 01 AUDIO
                TITLE "Erstes Kapitel"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Zweites Kapitel"
                INDEX 00 12:29:70
                INDEX 01 12:30:37
            FILE "Buch Teil 2.mp3" MP3
              TRACK 03 AUDIO
                TITLE "Drittes Kapitel"
                INDEX 01 00:00:00
        """.trimIndent()
        val parsed = CueSheet.parse(cue)
        assertEquals(setOf("Buch Teil 1.mp3", "Buch Teil 2.mp3"), parsed.keys)
        val first = parsed["Buch Teil 1.mp3"]!!
        assertEquals(listOf("Erstes Kapitel", "Zweites Kapitel"), first.map { it.title })
        assertEquals(750_493L, first[1].startMs)
        assertNotNull(parsed["Buch Teil 2.mp3"])
    }
}
