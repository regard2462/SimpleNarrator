package app.simplenarrator.export

import app.simplenarrator.testutil.sa.ArraySource
import app.simplenarrator.testutil.sa.Id3Chapters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** ID3v2.3 mit Kapiteln: Rückweg über den Kapitelleser aus Simple Audiobooks und einen kleinen Rahmenleser. */
class Id3TagTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val meta = AudioMeta("Weiße Nächte 𝄞", "Ärika Müster", jpeg, "image/jpeg")
    private val chapters = listOf(
        ChapterMark("Anfang", 0, 1000),
        ChapterMark("Mitte mit Ümlaut", 1000, 2200),
        ChapterMark("Ende", 2200, 3000),
    )

    private fun fixture(name: String): File =
        File(tmp.root, name).apply { writeBytes(Id3TagTest::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { it.readBytes() }) }

    private fun encodedMp3(): File {
        val rate = 22050
        val buf = ByteBuffer.allocate(rate * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate * 3) buf.putShort((8000 * sin(2 * PI * 440 * i / rate)).toInt().toShort())
        val pcm = File(tmp.root, "in.pcm").apply { writeBytes(buf.array()) }
        return File(tmp.root, "book.mp3").also { Mp3Encoder.encode(pcm, rate, it, 64) }
    }

    /** Rahmen eines v2.3-Tags: ID → Inhalte (CHAP/CTOC kommen mehrfach vor). */
    private fun frames(b: ByteArray): List<Pair<String, ByteArray>> {
        assertEquals("ID3", String(b, 0, 3, Charsets.ISO_8859_1))
        assertEquals(3, b[3].toInt())
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val out = ArrayList<Pair<String, ByteArray>>()
        var p = 10
        while (p + 10 <= 10 + size) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val len = Mp4TestKit.be(b, p + 4, 4).toInt()
            out += id to b.copyOfRange(p + 10, p + 10 + len)
            p += 10 + len
        }
        assertEquals(10 + size, p)
        return out
    }

    private fun tagEnd(b: ByteArray) = 10 + (((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F))

    private fun utf16(body: ByteArray): String {
        assertEquals(1, body[0].toInt())
        assertEquals(0xFF.toByte(), body[1]); assertEquals(0xFE.toByte(), body[2])
        return String(body, 3, body.size - 3, Charsets.UTF_16LE)
    }

    @Test
    fun writeAndReadBack() {
        val mp3 = encodedMp3()
        val audio = mp3.readBytes()
        Id3Tag.write(mp3, meta, chapters)
        val b = mp3.readBytes()

        val marks = Id3Chapters.read(ArraySource(b))
        assertEquals(chapters.map { it.title }, marks.map { it.title })
        assertEquals(chapters.map { it.startMs }, marks.map { it.startMs })

        val f = frames(b)
        val single = f.groupBy { it.first }
        assertEquals("Weiße Nächte 𝄞", utf16(single.getValue("TIT2").single().second))
        assertEquals("Ärika Müster", utf16(single.getValue("TPE1").single().second))
        assertEquals("Weiße Nächte 𝄞", utf16(single.getValue("TALB").single().second))
        assertEquals("Audiobook", utf16(single.getValue("TCON").single().second))

        val apic = single.getValue("APIC").single().second
        assertEquals(0, apic[0].toInt())
        val mimeEnd = (1 until apic.size).first { apic[it] == 0.toByte() }
        assertEquals("image/jpeg", String(apic, 1, mimeEnd - 1, Charsets.ISO_8859_1))
        assertEquals(3, apic[mimeEnd + 1].toInt())
        assertEquals(0, apic[mimeEnd + 2].toInt())
        assertArrayEquals(jpeg, apic.copyOfRange(mimeEnd + 3, apic.size))

        val ctoc = single.getValue("CTOC").single().second
        assertEquals("toc", String(ctoc, 0, 3, Charsets.ISO_8859_1))
        assertEquals(0, ctoc[3].toInt())
        assertEquals(0x03, ctoc[4].toInt())       // oberste Ebene + geordnet
        assertEquals(3, ctoc[5].toInt())
        assertEquals("chp0\u0000chp1\u0000chp2\u0000", String(ctoc, 6, ctoc.size - 6, Charsets.ISO_8859_1))

        val chaps = single.getValue("CHAP").map { it.second }
        assertEquals(3, chaps.size)
        chaps.forEachIndexed { i, c ->
            assertEquals("chp$i\u0000", String(c, 0, 5, Charsets.ISO_8859_1))
            assertEquals(chapters[i].startMs, Mp4TestKit.be(c, 5, 4))
            assertEquals(chapters[i].endMs, Mp4TestKit.be(c, 9, 4))
            assertEquals(0xFFFFFFFFL, Mp4TestKit.be(c, 13, 4))
            assertEquals(0xFFFFFFFFL, Mp4TestKit.be(c, 17, 4))
            assertEquals("TIT2", String(c, 21, 4, Charsets.ISO_8859_1))
            val len = Mp4TestKit.be(c, 25, 4).toInt()
            assertEquals(chapters[i].title, utf16(c.copyOfRange(31, 31 + len)))
        }

        // Ton unverändert hinter dem Tag, Rahmenkette bis zum Ende
        assertArrayEquals(audio, b.copyOfRange(tagEnd(b), b.size))
        assertEquals(b.size, MpegFrames.scan(b).endOffset)
        assertFalse(File(tmp.root, "book.mp3.part").exists())
    }

    @Test
    fun replacesExistingTags() {
        for (name in listOf("chapters23.mp3", "chapters24.mp3")) {
            val f = fixture(name)
            val orig = f.readBytes()
            val origAudio = orig.copyOfRange(tagEnd(orig), orig.size)
            assertEquals(3, Id3Chapters.read(ArraySource(orig)).size)
            Id3Tag.write(f, meta, listOf(ChapterMark("Eins", 0, 5000), ChapterMark("Zwei", 5000, 12_000)))
            val b = f.readBytes()
            val marks = Id3Chapters.read(ArraySource(b))
            assertEquals(name, listOf("Eins", "Zwei"), marks.map { it.title })
            assertEquals(listOf(0L, 5000L), marks.map { it.startMs })
            assertArrayEquals(name, origAudio, b.copyOfRange(tagEnd(b), b.size))
            assertFalse("only one tag", String(b, tagEnd(b), 3, Charsets.ISO_8859_1) == "ID3")
            // zweites Schreiben ergibt dieselbe Datei
            Id3Tag.write(f, meta, listOf(ChapterMark("Eins", 0, 5000), ChapterMark("Zwei", 5000, 12_000)))
            assertArrayEquals(b, f.readBytes())
        }
    }

    @Test
    fun moreThan255ChaptersUseNestedToc() {
        val mp3 = encodedMp3()
        val many = (0 until 300).map { ChapterMark("K${it + 1}", it * 10L, (it + 1) * 10L) }
        Id3Tag.write(mp3, AudioMeta("T", "A"), many)
        val b = mp3.readBytes()
        val marks = Id3Chapters.read(ArraySource(b))
        assertEquals(300, marks.size)
        assertEquals("K300", marks.last().title)
        val ctocs = frames(b).filter { it.first == "CTOC" }.map { it.second }
        assertEquals(3, ctocs.size)
        assertEquals(0x03, ctocs[0][4].toInt())
        assertEquals(2, ctocs[0][5].toInt())
        assertEquals(0x01, ctocs[1][5].toInt())
        assertEquals(255, ctocs[1][6].toInt() and 0xFF)
        assertEquals(45, ctocs[2][6].toInt())
        assertTrue(frames(b).none { it.first == "APIC" })
    }
}
