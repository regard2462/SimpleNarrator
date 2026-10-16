package app.simplenarrator.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Schreibt Kapitel und iTunes-Metadaten in MP4/M4A-Dateien (AAC aus Androids MediaMuxer, aber auch ffmpeg-Dateien).
 *
 *  - Nero-Kapitel: moov/udta/chpl, Version 1, Startzeiten in 100 ns. Das Format fasst höchstens 255 Einträge.
 *  - QuickTime-Kapitelspur: eine abgeschaltete Textspur, auf die die erste Tonspur per tref/chap zeigt.
 *    Apple-Player lesen nur diese Spur, viele Android-Player beide. Die Textproben liegen in einem kleinen
 *    mdat am Dateiende, deshalb verschiebt die Spur keine vorhandenen Daten. Aufbau wie bei ffmpeg.
 *  - iTunes-Metadaten: moov/udta/meta (hdlr mdir) /ilst mit ©nam, ©ART, aART, ©alb, ©gen, stik (2 = Hörbuch), covr.
 *
 * Vorhandene chpl, Kapitelspuren und ilst-Einträge mit denselben Schlüsseln werden ersetzt, andere ilst-Einträge
 * (z. B. ©too) und udta-Kinder bleiben erhalten. Liegt moov vor mdat und ändert sich seine Größe, wird zuerst eine
 * direkt folgende free-Box angepasst (so legt MediaMuxer Platz an). Geht das nicht, werden alle stco/co64-Einträge
 * aller Spuren verschoben. Ein Umstieg von stco auf co64 ist nicht eingebaut: läuft ein 32-Bit-Eintrag über, bricht
 * write mit einer IOException ab und die Datei bleibt unverändert.
 *
 * Das Ergebnis entsteht in einer temporären Datei neben dem Original und ersetzt es danach in einem Schritt.
 */
object Mp4Tags {
    private const val CHPL_MAX = 255
    private const val TITLE_MAX_BYTES = 1024
    private const val MAX_MOOV = 256L * 1024 * 1024
    private const val U32_MAX = 0xFFFFFFFFL
    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")
    private val TEXT_HANDLERS = setOf("text", "sbtl")

    private const val NAM = "©nam"
    private const val ART = "©ART"
    private const val ALB = "©alb"
    private const val GEN = "©gen"

    /** gmhd-Inhalt (gmin + text) und text-Probenbeschreibung, wie ffmpeg sie für Kapitelspuren schreibt. */
    private val GMHD_PAYLOAD = hex(
        "00000018676d696e000000000040800080008000000000000000002c74657874000100000000000000000000000000000001" +
            "000000000000000000000000000040000000"
    )
    private val TEXT_ENTRY_PAYLOAD = hex(
        "00000000000000010000000100000000000000000000000000000000000000010000000000000000000d667461620001000100"
    )
    /** encd-Atom nach jeder Textprobe: Text ist UTF-8. */
    private val ENCD = hex("0000000c656e636400000100")

    private class TopBox(val type: String, val start: Long, val size: Long, val sizeZero: Boolean) {
        val end: Long get() = start + size
    }

    private class MBox(val type: String, val start: Int, val header: Int, val size: Int, val sizeZero: Boolean) {
        val payload: Int get() = start + header
        val end: Int get() = start + size
    }

    private class Trak(val box: MBox, val id: Long, val handler: String, val chapRefs: List<Long>)

    private sealed class Seg {
        class Range(val start: Long, val end: Long) : Seg()
        class Bytes(val data: ByteArray) : Seg()
        class Zeros(val count: Long) : Seg()
    }

    /**
     * Schreibt [meta] und [chapters] in [file]. Mit [chapterTrack] = false entsteht nur die Nero-Liste.
     * Eine leere Kapitelliste entfernt vorhandene Kapitel.
     */
    fun write(file: File, meta: AudioMeta, chapters: List<ChapterMark>, chapterTrack: Boolean = true) {
        val tmp = TagFiles.tempFor(file)
        try {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val segs = plan(ch, meta, chapters, chapterTrack)
                FileOutputStream(tmp).use { fos -> writeSegments(ch, segs, fos.channel) }
            }
            TagFiles.replace(tmp, file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ------------------------------------------------------------------ Planung

    private fun plan(ch: FileChannel, meta: AudioMeta, chapters: List<ChapterMark>, wantTrack: Boolean): List<Seg> {
        val fileLen = ch.size()
        val (boxes, tailStart) = topLevel(ch)
        val moovIdx = boxes.indexOfFirst { it.type == "moov" }
        if (moovIdx < 0) throw IOException("No moov box, not an MP4 file")
        if (boxes.count { it.type == "moov" } > 1) throw IOException("More than one moov box")
        val moovTop = boxes[moovIdx]
        if (moovTop.size > MAX_MOOV) throw IOException("moov box too large (${moovTop.size} bytes)")
        val moov = ByteArray(moovTop.size.toInt())
        readFully(ch, ByteBuffer.wrap(moov), moovTop.start)
        val moovHeader = if (u32(moov, 0) == 1L) 16 else 8
        val kids = children(moov, moovHeader, moov.size)
        val mvhd = kids.firstOrNull { it.type == "mvhd" } ?: throw IOException("moov without mvhd")
        val traks = kids.filter { it.type == "trak" }.map { parseTrak(moov, it) }

        // Vorhandene Kapitelspuren (Ziel einer tref/chap-Referenz mit Text-Handler) fallen weg
        val chapIds = traks.flatMap { it.chapRefs }.toSet()
        val removed = traks.filter { it.id in chapIds && it.handler in TEXT_HANDLERS }
        val kept = traks.filter { it !in removed }
        val audio = kept.firstOrNull { it.handler == "soun" }

        val v1 = moov[mvhd.payload].toInt() == 1
        val timescale = u32(moov, mvhd.payload + if (v1) 20 else 12)
        val duration = if (v1) u64(moov, mvhd.payload + 24) else u32(moov, mvhd.payload + 16)
        val fileMs = if (timescale > 0) duration * 1000 / timescale else 0L
        val marks = normalize(chapters, fileMs)
        val totalMs = if (fileMs > 0) fileMs else marks.maxOfOrNull { maxOf(it.endMs, it.startMs + 1) } ?: 0L

        // Kapitel-mdat eines früheren Laufs am Dateiende: nur die alte Kapitelspur zeigt hinein
        val keptOffsets = kept.flatMap { chunkOffsets(moov, it.box) }
        val removedOffsets = removed.flatMap { chunkOffsets(moov, it.box) }
        val last = boxes.last()
        val dropLast = boxes.lastIndex > moovIdx && last.type == "mdat" && tailStart == fileLen &&
            removedOffsets.any { it >= last.start && it < last.end } &&
            keptOffsets.none { it >= last.start && it < last.end }
        val after = boxes.subList(moovIdx + 1, if (dropLast) boxes.size - 1 else boxes.size)

        var addTrack = wantTrack && audio != null && marks.isNotEmpty() && totalMs > 0 && timescale > 0
        // Eine Box mit Größe 0 reicht bis zum Dateiende; hinter ihr anhängen geht nur mit 32-Bit-Größe
        if (after.any { it.sizeZero && it.size > U32_MAX }) addTrack = false

        val samples = if (addTrack) marks.map { textSample(it.title) } else emptyList()
        val chapData = samples.fold(ByteArrayOutputStream()) { o, s -> o.write(s); o }.toByteArray()
        // Neue ID direkt hinter der höchsten verbliebenen; die ID einer ersetzten Kapitelspur wird so wiederverwendet
        val newTrackId = if (addTrack) (kept.maxOfOrNull { it.id } ?: 0L) + 1 else 0L

        fun build(shift: Long, chapterOffset: Long, co64: Boolean): ByteArray = buildMoov(
            moov, kids, kept, removed, audio, meta, marks, shift, moovTop.end,
            if (addTrack) ChapterTrackSpec(newTrackId, marks.map { it.startMs }, samples, totalMs, timescale, chapterOffset, co64) else null,
        )

        val co64 = addTrack && fileLen + moov.size + chapData.size + 4096 > U32_MAX
        val sizeProbe = build(0, 0, co64).size.toLong()
        val delta = sizeProbe - moovTop.size

        // Daten hinter moov müssen ihre Position behalten oder die Verweise darauf werden verschoben
        val needStable = after.any { it.type == "mdat" || it.type == "moof" }
        var padding = 0L
        var skipFree = false
        var shift = 0L
        if (needStable && delta != 0L) {
            val free = after.firstOrNull()?.takeIf { (it.type == "free" || it.type == "skip") && !it.sizeZero }
            when {
                free != null && (free.size - delta >= 8 || free.size - delta == 0L) -> { skipFree = true; padding = free.size - delta }
                delta < 0 && -delta >= 8 -> padding = -delta
                else -> shift = delta
            }
        }
        if (shift != 0L && after.any { it.type == "moof" }) throw IOException("Fragmented MP4 with moov before the fragments is not supported")

        val afterBoxes = if (skipFree) after.drop(1) else after
        val keepGarbage = !addTrack && tailStart < fileLen
        val lenBefore = moovTop.start + sizeProbe + padding + afterBoxes.sumOf { it.size } + (if (keepGarbage) fileLen - tailStart else 0L)
        val newMoov = build(shift, lenBefore + 8, co64)
        if (newMoov.size.toLong() != sizeProbe) throw IllegalStateException("moov size changed between passes")

        val segs = ArrayList<Seg>()
        segs += Seg.Range(0, moovTop.start)
        segs += Seg.Bytes(newMoov)
        if (padding > 0) {
            segs += Seg.Bytes(boxHeader("free", padding))
            segs += Seg.Zeros(padding - 8)
        }
        for (b in afterBoxes) {
            if (b.sizeZero && addTrack) {
                segs += Seg.Bytes(boxHeader(b.type, b.size))
                segs += Seg.Range(b.start + 8, b.end)
            } else {
                segs += Seg.Range(b.start, b.end)
            }
        }
        if (keepGarbage) segs += Seg.Range(tailStart, fileLen)
        if (addTrack) {
            segs += Seg.Bytes(boxHeader("mdat", 8L + chapData.size))
            segs += Seg.Bytes(chapData)
        }
        return segs
    }

    /** Sortiert, entfernt doppelte Startzeiten und alles hinter dem Dateiende. */
    private fun normalize(chapters: List<ChapterMark>, fileMs: Long): List<ChapterMark> =
        chapters.filter { it.startMs >= 0 && (fileMs <= 0 || it.startMs < fileMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }

    private class ChapterTrackSpec(
        val id: Long, val starts: List<Long>, val samples: List<ByteArray>, val totalMs: Long, val movieTimescale: Long,
        val offset: Long, val co64: Boolean,
    )

    // ------------------------------------------------------------------ moov neu zusammensetzen

    private fun buildMoov(
        src: ByteArray, kids: List<MBox>, kept: List<Trak>, removed: List<Trak>, audio: Trak?,
        meta: AudioMeta, marks: List<ChapterMark>, shift: Long, threshold: Long, track: ChapterTrackSpec?,
    ): ByteArray {
        val work = src.copyOf()
        if (shift != 0L) kept.forEach { patchOffsets(work, it.box.payload, it.box.end, threshold, shift) }
        val removedStarts = removed.map { it.box.start }.toSet()
        val keptByStart = kept.associateBy { it.box.start }
        val lastKept = kept.maxOfOrNull { it.box.start } ?: -1
        val out = ByteArrayOutputStream(src.size + 4096)
        var udtaDone = false
        for (c in kids) {
            when {
                c.type == "mvhd" -> {
                    val b = slice(work, c)
                    if (track != null) {
                        val pos = c.header + if (b[c.header].toInt() == 1) 108 else 96
                        if (pos + 4 <= b.size) putU32(b, pos, maxOf(u32(b, pos), track.id + 1))
                    }
                    out.write(b)
                }
                c.type == "trak" && c.start in removedStarts -> {}
                c.type == "trak" -> {
                    val t = keptByStart.getValue(c.start)
                    val newRef = if (track != null && t === audio) track.id else null
                    out.write(if (t.chapRefs.isNotEmpty() || newRef != null) rebuildTrak(work, t, newRef) else slice(work, c))
                    if (track != null && c.start == lastKept) out.write(chapterTrak(track))
                }
                c.type == "udta" -> {
                    out.write(buildUdta(work, c, meta, marks)); udtaDone = true
                }
                else -> out.write(slice(work, c))
            }
        }
        if (!udtaDone) out.write(buildUdta(work, null, meta, marks))
        return box("moov", out.toByteArray())
    }

    /** trak ohne alte chap-Verweise, auf Wunsch mit neuem Verweis auf die Kapitelspur. */
    private fun rebuildTrak(b: ByteArray, t: Trak, newChapId: Long?): ByteArray {
        val kids = children(b, t.box.payload, t.box.end)
        val hasTref = kids.any { it.type == "tref" }
        val out = ByteArrayOutputStream()
        for (c in kids) {
            if (c.type == "tref") {
                val parts = children(b, c.payload, c.end).filter { it.type != "chap" }.map { slice(b, it) }.toMutableList()
                if (newChapId != null) parts += box("chap", u32Bytes(newChapId))
                if (parts.isNotEmpty()) out.write(box("tref", concat(parts)))
            } else {
                out.write(slice(b, c))
                if (c.type == "tkhd" && !hasTref && newChapId != null) out.write(box("tref", box("chap", u32Bytes(newChapId))))
            }
        }
        return box("trak", out.toByteArray())
    }

    private fun buildUdta(b: ByteArray, udta: MBox?, meta: AudioMeta, marks: List<ChapterMark>): ByteArray {
        val keptKids = ByteArrayOutputStream()
        val oldItems = ArrayList<Pair<String, ByteArray>>()
        if (udta != null) {
            for (c in children(b, udta.payload, udta.end)) when (c.type) {
                "chpl" -> {}
                "meta" -> oldItems += ilstItems(b, c)
                else -> keptKids.write(slice(b, c))
            }
        }
        val ours = ourItems(meta)
        val replaced = ours.map { it.first }.toMutableSet()
        if (GEN in replaced) replaced += "gnre"
        val items = oldItems.filter { it.first !in replaced } + ours
        val out = ByteArrayOutputStream()
        out.write(keptKids.toByteArray())
        if (items.isNotEmpty()) out.write(metaBox(items.map { it.second }))
        if (marks.isNotEmpty()) out.write(chpl(marks))
        return box("udta", out.toByteArray())
    }

    /** Einträge einer iTunes-ilst; andere meta-Arten (fremder Handler) werden verworfen. */
    private fun ilstItems(b: ByteArray, meta: MBox): List<Pair<String, ByteArray>> {
        if (meta.size < meta.header + 8) return emptyList()
        // iTunes: meta ist eine FullBox; QuickTime-Variante ohne Version/Flags kommt auch vor
        val start = if (fourcc(b, meta.payload + 4) == "hdlr") meta.payload else meta.payload + 4
        val kids = runCatching { children(b, start, meta.end) }.getOrElse { return emptyList() }
        val hdlr = kids.firstOrNull { it.type == "hdlr" } ?: return emptyList()
        if (hdlr.size < hdlr.header + 12 || fourcc(b, hdlr.payload + 8) != "mdir") return emptyList()
        val ilst = kids.firstOrNull { it.type == "ilst" } ?: return emptyList()
        return children(b, ilst.payload, ilst.end).map { it.type to slice(b, it) }
    }

    private fun ourItems(meta: AudioMeta): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        fun text(key: String, value: String) {
            if (value.isNotBlank()) out += key to box(key, dataAtom(1, value.trim().toByteArray(Charsets.UTF_8)))
        }
        text(NAM, meta.title)
        text(ART, meta.author)
        text("aART", meta.author)
        text(ALB, meta.album)
        text(GEN, meta.genre)
        out += "stik" to box("stik", dataAtom(21, byteArrayOf(2)))
        val type = meta.coverType()
        if (type != null) out += "covr" to box("covr", dataAtom(if (type == AudioMeta.PNG) 14 else 13, meta.cover!!))
        return out
    }

    private fun dataAtom(type: Int, value: ByteArray): ByteArray = box("data", concat(listOf(u32Bytes(type.toLong()), u32Bytes(0), value)))

    private fun metaBox(items: List<ByteArray>): ByteArray {
        val hdlr = fullBox("hdlr", 0, 0, concat(listOf(u32Bytes(0), "mdir".toByteArray(Charsets.ISO_8859_1),
            "appl".toByteArray(Charsets.ISO_8859_1), u32Bytes(0), u32Bytes(0), byteArrayOf(0))))
        return fullBox("meta", 0, 0, concat(listOf(hdlr, box("ilst", concat(items)))))
    }

    private fun chpl(marks: List<ChapterMark>): ByteArray {
        val list = marks.take(CHPL_MAX)
        val o = ByteArrayOutputStream()
        o.write(u32Bytes(0))          // reserviert (Version 1)
        o.write(list.size)
        for (m in list) {
            o.write(u64Bytes(m.startMs * 10_000))
            val t = utf8Prefix(m.title, 255)
            o.write(t.size); o.write(t)
        }
        return fullBox("chpl", 1, 0, o.toByteArray())
    }

    // ------------------------------------------------------------------ QuickTime-Kapitelspur

    private fun textSample(title: String): ByteArray {
        val t = utf8Prefix(title, TITLE_MAX_BYTES)
        return concat(listOf(byteArrayOf((t.size shr 8).toByte(), t.size.toByte()), t, ENCD))
    }

    private fun chapterTrak(s: ChapterTrackSpec): ByteArray {
        val n = s.samples.size
        val starts = s.starts
        val totalMs = s.totalMs
        val tkhdDuration = totalMs * s.movieTimescale / 1000
        val tkhd = run {
            val v1 = tkhdDuration > U32_MAX
            val o = ByteArrayOutputStream()
            if (v1) { o.write(u64Bytes(0)); o.write(u64Bytes(0)) } else { o.write(u32Bytes(0)); o.write(u32Bytes(0)) }
            o.write(u32Bytes(s.id)); o.write(u32Bytes(0))
            o.write(if (v1) u64Bytes(tkhdDuration) else u32Bytes(tkhdDuration))
            o.write(ByteArray(8))                          // reserviert
            o.write(ByteArray(8))                          // layer, alternate_group, volume, reserviert
            o.write(identityMatrix())
            o.write(ByteArray(8))                          // Breite, Höhe
            fullBox("tkhd", if (v1) 1 else 0, 2, o.toByteArray())   // Flag 2: im Film, nicht aktiv
        }
        val mdhd = run {
            val v1 = totalMs > U32_MAX
            val o = ByteArrayOutputStream()
            if (v1) { o.write(u64Bytes(0)); o.write(u64Bytes(0)) } else { o.write(u32Bytes(0)); o.write(u32Bytes(0)) }
            o.write(u32Bytes(1000))
            o.write(if (v1) u64Bytes(totalMs) else u32Bytes(totalMs))
            o.write(byteArrayOf(0x55, 0xC4.toByte(), 0, 0))  // Sprache "und", pre_defined
            fullBox("mdhd", if (v1) 1 else 0, 0, o.toByteArray())
        }
        val hdlr = fullBox("hdlr", 0, 0, concat(listOf(u32Bytes(0), "text".toByteArray(Charsets.ISO_8859_1), ByteArray(12),
            "SubtitleHandler".toByteArray(Charsets.ISO_8859_1), byteArrayOf(0))))
        val dinf = box("dinf", fullBox("dref", 0, 0, concat(listOf(u32Bytes(1), fullBox("url ", 0, 1, ByteArray(0))))))

        // Probendauern aus den Startzeiten (Zeitbasis 1 ms). Die Spur muss bei 0 beginnen,
        // die erste Probe deckt daher auch einen späteren ersten Kapitelstart ab.
        val stts = run {
            val o = ByteArrayOutputStream()
            val entries = ArrayList<Pair<Long, Long>>()
            for (i in 0 until n) {
                val start = if (i == 0) 0L else starts[i]
                val end = if (i + 1 < n) starts[i + 1] else totalMs
                val d = maxOf(1L, end - start)
                if (entries.isNotEmpty() && entries.last().second == d) {
                    entries[entries.lastIndex] = entries.last().first + 1 to d
                } else entries += 1L to d
            }
            o.write(u32Bytes(entries.size.toLong()))
            entries.forEach { (count, d) -> o.write(u32Bytes(count)); o.write(u32Bytes(d)) }
            fullBox("stts", 0, 0, o.toByteArray())
        }
        val stsd = fullBox("stsd", 0, 0, concat(listOf(u32Bytes(1), box("text", TEXT_ENTRY_PAYLOAD))))
        val stsc = fullBox("stsc", 0, 0, concat(listOf(u32Bytes(1), u32Bytes(1), u32Bytes(n.toLong()), u32Bytes(1))))
        val stsz = run {
            val o = ByteArrayOutputStream()
            o.write(u32Bytes(0)); o.write(u32Bytes(n.toLong()))
            s.samples.forEach { o.write(u32Bytes(it.size.toLong())) }
            fullBox("stsz", 0, 0, o.toByteArray())
        }
        val chunk = if (s.co64) fullBox("co64", 0, 0, concat(listOf(u32Bytes(1), u64Bytes(s.offset))))
        else {
            if (s.offset > U32_MAX) throw IOException("Chapter data offset exceeds 32 bits")
            fullBox("stco", 0, 0, concat(listOf(u32Bytes(1), u32Bytes(s.offset))))
        }
        val stbl = box("stbl", concat(listOf(stsd, stts, stsc, stsz, chunk)))
        val minf = box("minf", concat(listOf(box("gmhd", GMHD_PAYLOAD), dinf, stbl)))
        val mdia = box("mdia", concat(listOf(mdhd, hdlr, minf)))
        return box("trak", concat(listOf(tkhd, mdia)))
    }

    private fun identityMatrix(): ByteArray = concat(listOf(
        u32Bytes(0x00010000), u32Bytes(0), u32Bytes(0),
        u32Bytes(0), u32Bytes(0x00010000), u32Bytes(0),
        u32Bytes(0), u32Bytes(0), u32Bytes(0x40000000),
    ))

    // ------------------------------------------------------------------ Boxen lesen

    private fun topLevel(ch: FileChannel): Pair<List<TopBox>, Long> {
        val len = ch.size()
        val out = ArrayList<TopBox>()
        val hdr = ByteBuffer.allocate(16)
        var pos = 0L
        while (len - pos >= 8) {
            hdr.clear(); hdr.limit(8)
            readFully(ch, hdr, pos)
            val a = hdr.array()
            var size = u32(a, 0)
            val type = fourcc(a, 4)
            var header = 8
            var zero = false
            if (size == 1L) {
                if (len - pos < 16) break
                hdr.clear(); hdr.limit(16)
                readFully(ch, hdr, pos)
                size = u64(a, 8)
                header = 16
            } else if (size == 0L) {
                size = len - pos; zero = true
            }
            if (size < header || size > len - pos) throw IOException("Broken MP4 structure at byte $pos ('$type')")
            out += TopBox(type, pos, size, zero)
            pos += size
        }
        if (out.isEmpty()) throw IOException("Not an MP4 file")
        return out to pos
    }

    private fun children(b: ByteArray, from: Int, to: Int): List<MBox> {
        val out = ArrayList<MBox>()
        var pos = from
        while (to - pos >= 8) {
            var size = u32(b, pos)
            val type = fourcc(b, pos + 4)
            var header = 8
            var zero = false
            if (size == 1L) {
                if (to - pos < 16) throw IOException("Broken box '$type'")
                size = u64(b, pos + 8); header = 16
            } else if (size == 0L) {
                size = (to - pos).toLong(); zero = true
            }
            if (size < header || size > to - pos) throw IOException("Broken box '$type' at $pos")
            out += MBox(type, pos, header, size.toInt(), zero)
            pos += size.toInt()
        }
        return out
    }

    private fun parseTrak(b: ByteArray, trak: MBox): Trak {
        var id = -1L
        var handler = ""
        val refs = ArrayList<Long>()
        for (c in children(b, trak.payload, trak.end)) when (c.type) {
            "tkhd" -> {
                val v1 = b[c.payload].toInt() == 1
                val p = c.payload + if (v1) 20 else 12
                if (p + 4 <= c.end) id = u32(b, p)
            }
            "tref" -> children(b, c.payload, c.end).filter { it.type == "chap" }.forEach { r ->
                for (i in 0 until (r.end - r.payload) / 4) refs += u32(b, r.payload + i * 4)
            }
            "mdia" -> children(b, c.payload, c.end).firstOrNull { it.type == "hdlr" }?.let { h ->
                if (h.payload + 12 <= h.end) handler = fourcc(b, h.payload + 8)
            }
        }
        return Trak(trak, id, handler, refs)
    }

    /** Alle Chunk-Offsets einer Spur (stco und co64). */
    private fun chunkOffsets(b: ByteArray, trak: MBox): List<Long> {
        val out = ArrayList<Long>()
        walkOffsets(b, trak.payload, trak.end) { pos, wide -> out += if (wide) u64(b, pos) else u32(b, pos) }
        return out
    }

    /** Verschiebt alle Offsets ab [threshold] um [shift]; 32-Bit-Überlauf ist ein Fehler. */
    private fun patchOffsets(b: ByteArray, from: Int, to: Int, threshold: Long, shift: Long) {
        walkOffsets(b, from, to) { pos, wide ->
            val v = if (wide) u64(b, pos) else u32(b, pos)
            if (v >= threshold) {
                val nv = v + shift
                if (nv < 0) throw IOException("Chunk offset would become negative")
                if (wide) putU64(b, pos, nv)
                else {
                    if (nv > U32_MAX) throw IOException("Chunk offset exceeds 32 bits (stco); co64 conversion is not supported")
                    putU32(b, pos, nv)
                }
            }
        }
    }

    private fun walkOffsets(b: ByteArray, from: Int, to: Int, visit: (pos: Int, wide: Boolean) -> Unit) {
        for (c in children(b, from, to)) {
            when (c.type) {
                in CONTAINERS -> walkOffsets(b, c.payload, c.end, visit)
                "stco", "co64" -> {
                    val wide = c.type == "co64"
                    val w = if (wide) 8 else 4
                    if (c.payload + 8 > c.end) throw IOException("Broken ${c.type}")
                    val n = u32(b, c.payload + 4)
                    if (n * w > c.end - c.payload - 8) throw IOException("Broken ${c.type}: $n entries")
                    for (i in 0 until n.toInt()) visit(c.payload + 8 + i * w, wide)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Schreiben

    private fun writeSegments(src: FileChannel, segs: List<Seg>, out: FileChannel) {
        val buf = ByteBuffer.allocate(256 * 1024)
        for (s in segs) when (s) {
            is Seg.Bytes -> writeAll(out, ByteBuffer.wrap(s.data))
            is Seg.Zeros -> {
                var left = s.count
                val zeros = ByteArray(minOf(left, 64L * 1024).toInt())
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    writeAll(out, ByteBuffer.wrap(zeros, 0, n)); left -= n
                }
            }
            is Seg.Range -> {
                var pos = s.start
                while (pos < s.end) {
                    buf.clear()
                    if (s.end - pos < buf.capacity()) buf.limit((s.end - pos).toInt())
                    val n = src.read(buf, pos)
                    if (n <= 0) throw IOException("Unexpected end of file at $pos")
                    buf.flip(); writeAll(out, buf); pos += n
                }
            }
        }
        out.force(true)
    }

    private fun writeAll(out: FileChannel, b: ByteBuffer) {
        while (b.hasRemaining()) out.write(b)
    }

    private fun readFully(ch: FileChannel, b: ByteBuffer, pos: Long) {
        var p = pos
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n <= 0) throw IOException("Unexpected end of file at $p")
            p += n
        }
    }

    // ------------------------------------------------------------------ Bytes

    private fun slice(b: ByteArray, c: MBox): ByteArray {
        val out = b.copyOfRange(c.start, c.end)
        if (c.sizeZero) putU32(out, 0, c.size.toLong())   // Größe 0 ("bis zum Ende") ausschreiben
        return out
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val total = 8L + body.size
        if (total > U32_MAX) throw IOException("Box '$type' too large")
        return concat(listOf(boxHeader(type, total), body))
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: ByteArray): ByteArray =
        box(type, concat(listOf(u32Bytes(((version.toLong() and 0xFF) shl 24) or (flags.toLong() and 0xFFFFFF)), body)))

    private fun boxHeader(type: String, size: Long): ByteArray =
        concat(listOf(u32Bytes(size), type.toByteArray(Charsets.ISO_8859_1)))

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { System.arraycopy(a, 0, out, p, a.size); p += a.size }
        return out
    }

    /** Höchstens [max] Bytes UTF-8, ohne ein Zeichen zu zerschneiden. */
    private fun utf8Prefix(s: String, max: Int): ByteArray {
        val all = s.trim().toByteArray(Charsets.UTF_8)
        if (all.size <= max) return all
        var end = max
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
        return all.copyOf(end)
    }

    private fun fourcc(b: ByteArray, off: Int) = String(b, off, 4, Charsets.ISO_8859_1)
    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
    private fun u64(b: ByteArray, off: Int): Long = (u32(b, off) shl 32) or u32(b, off + 4)
    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte(); b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }
    private fun putU64(b: ByteArray, off: Int, v: Long) { putU32(b, off, v ushr 32); putU32(b, off + 4, v and U32_MAX) }
    private fun u32Bytes(v: Long) = ByteArray(4).also { putU32(it, 0, v) }
    private fun u64Bytes(v: Long) = ByteArray(8).also { putU64(it, 0, v) }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
