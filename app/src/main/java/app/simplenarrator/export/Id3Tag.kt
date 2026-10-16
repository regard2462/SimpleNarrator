package app.simplenarrator.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * ID3v2.3-Tag für MP3-Hörbücher: TIT2, TPE1, TALB, TCON, APIC (Vorderseite) und Kapitel nach dem
 * ID3-Kapitelzusatz (CTOC + CHAP). v2.3 statt v2.4, weil ältere Player und Autoradios v2.4 oft nicht lesen.
 *
 * Texte stehen in UTF-16 mit BOM (Kodierungsbyte 1). APIC nutzt Kodierung 0 mit leerer Beschreibung,
 * das lesen auch Player, die bei UTF-16 in APIC scheitern. Ein CTOC fasst höchstens 255 Einträge; bei mehr
 * Kapiteln verweist das oberste CTOC auf geordnete Unter-CTOCs mit je bis zu 255 CHAP-Rahmen.
 *
 * Ein vorhandenes ID3v2-Tag am Dateianfang wird ersetzt, ein ID3v1-Tag am Ende bleibt.
 * Das Ergebnis entsteht in einer temporären Datei und ersetzt das Original danach in einem Schritt.
 */
object Id3Tag {
    private const val CTOC_MAX = 255
    private const val MAX_TAG = (1 shl 28) - 1

    fun write(mp3: File, meta: AudioMeta, chapters: List<ChapterMark>) {
        val tag = build(meta, chapters)
        val tmp = TagFiles.tempFor(mp3)
        try {
            RandomAccessFile(mp3, "r").use { raf ->
                val audioStart = existingTagEnd(raf)
                tmp.outputStream().use { out ->
                    out.write(tag)
                    val buf = ByteArray(256 * 1024)
                    raf.seek(audioStart)
                    while (true) {
                        val n = raf.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
            TagFiles.replace(tmp, mp3)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Das komplette Tag (Kopf + Rahmen) als Bytes. */
    fun build(meta: AudioMeta, chapters: List<ChapterMark>): ByteArray {
        val frames = ByteArrayOutputStream()
        fun text(id: String, value: String) {
            if (value.isNotBlank()) frames.write(frame(id, textBody(value.trim())))
        }
        text("TIT2", meta.title)
        text("TPE1", meta.author)
        text("TALB", meta.album)
        text("TCON", meta.genre)
        meta.coverType()?.let { mime -> frames.write(frame("APIC", apicBody(mime, meta.cover!!))) }
        frames.write(chapterFrames(chapters))

        val body = frames.toByteArray()
        if (body.size > MAX_TAG) throw IOException("ID3 tag too large (${body.size} bytes)")
        val out = ByteArrayOutputStream(body.size + 10)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(syncsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun chapterFrames(chapters: List<ChapterMark>): ByteArray {
        val marks = chapters.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }
        if (marks.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val ids = marks.indices.map { "chp$it" }
        if (marks.size <= CTOC_MAX) {
            out.write(frame("CTOC", ctocBody("toc", topLevel = true, children = ids)))
        } else {
            val groups = ids.chunked(CTOC_MAX)
            val groupIds = groups.indices.map { "toc$it" }
            out.write(frame("CTOC", ctocBody("toc", topLevel = true, children = groupIds)))
            groups.forEachIndexed { i, g -> out.write(frame("CTOC", ctocBody(groupIds[i], topLevel = false, children = g))) }
        }
        marks.forEachIndexed { i, m ->
            val end = maxOf(m.endMs, m.startMs)
            val b = ByteArrayOutputStream()
            b.write(latin1z(ids[i]))
            b.write(u32(m.startMs)); b.write(u32(end))
            b.write(u32(0xFFFFFFFFL)); b.write(u32(0xFFFFFFFFL))   // keine Byte-Offsets
            b.write(frame("TIT2", textBody(m.title.trim())))
            out.write(frame("CHAP", b.toByteArray()))
        }
        return out.toByteArray()
    }

    /** CTOC-Inhalt: Element-ID, Flags (Bit 1 oberste Ebene, Bit 0 geordnet), Anzahl, Kinder-IDs. */
    private fun ctocBody(id: String, topLevel: Boolean, children: List<String>): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(latin1z(id))
        b.write((if (topLevel) 0x02 else 0) or 0x01)
        b.write(children.size)
        children.forEach { b.write(latin1z(it)) }
        return b.toByteArray()
    }

    /** Kodierung 1 = UTF-16 mit BOM (hier little endian), ohne abschließende Null. */
    private fun textBody(value: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(1); b.write(0xFF); b.write(0xFE)
        b.write(value.toByteArray(Charsets.UTF_16LE))
        return b.toByteArray()
    }

    private fun apicBody(mime: String, data: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(data.size + 32)
        b.write(0)                 // Kodierung ISO-8859-1
        b.write(latin1z(mime))
        b.write(0x03)              // Bildtyp: Vorderseite
        b.write(0)                 // leere Beschreibung
        b.write(data)
        return b.toByteArray()
    }

    private fun frame(id: String, body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(body.size + 10)
        b.write(id.toByteArray(Charsets.ISO_8859_1))
        b.write(u32(body.size.toLong()))   // v2.3: normale 32-Bit-Größe, nicht syncsafe
        b.write(0); b.write(0)
        b.write(body)
        return b.toByteArray()
    }

    /** Position hinter allen ID3v2-Tags am Dateianfang (0, wenn keins da ist). */
    private fun existingTagEnd(raf: RandomAccessFile): Long {
        var pos = 0L
        val h = ByteArray(10)
        while (pos + 10 <= raf.length()) {
            raf.seek(pos)
            raf.readFully(h)
            if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) break
            val major = h[3].toInt() and 0xFF
            if (major !in 2..4 || (6..9).any { h[it].toInt() and 0x80 != 0 }) break
            val size = ((h[6].toLong() and 0x7F) shl 21) or ((h[7].toLong() and 0x7F) shl 14) or
                ((h[8].toLong() and 0x7F) shl 7) or (h[9].toLong() and 0x7F)
            val footer = if (major == 4 && (h[5].toInt() and 0x10) != 0) 10 else 0
            val next = pos + 10 + size + footer
            if (next > raf.length()) throw IOException("Broken ID3v2 tag")
            pos = next
        }
        return pos
    }

    private fun latin1z(s: String): ByteArray = (s + "\u0000").toByteArray(Charsets.ISO_8859_1)

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun syncsafe(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte(),
    )
}
