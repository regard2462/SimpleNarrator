package app.simplenarrator.testutil.sa


/**
 * Liest Kapitel aus MP4/M4A/M4B-Dateien.
 *
 * Unterstützt werden die QuickTime-Kapitelspur (tref/chap, von iTunes, ffmpeg und den meisten
 * Hörbuchwerkzeugen geschrieben) und die Nero-Kapitelliste (moov/udta/chpl).
 */
object Mp4Chapters {

    data class Result(val marks: List<Mark>, val durationMs: Long?)

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val payload: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    private class TrackInfo {
        var id = -1L
        var chapterRefs = emptyList<Long>()
        var timescale = 0L
        var handler = ""
        var stts = emptyList<Pair<Long, Long>>()
        var sampleSizes = LongArray(0)
        var stsc = emptyList<Triple<Long, Long, Long>>()
        var chunkOffsets = LongArray(0)
    }

    private const val MAX_ENTRIES = 20_000

    fun read(src: ByteSource): Result? {
        val moov = children(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return null
        var durationMs: Long? = null
        var nero: List<Mark> = emptyList()
        val tracks = ArrayList<TrackInfo>()
        for (b in children(src, moov.payload, moov.end)) {
            when (b.type) {
                "mvhd" -> durationMs = readMvhd(src, b)
                "udta" -> children(src, b.payload, b.end).firstOrNull { it.type == "chpl" }?.let { nero = readChpl(src, it) }
                "trak" -> tracks += readTrak(src, b)
            }
        }
        val refIds = tracks.flatMap { it.chapterRefs }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in refIds && it.handler in setOf("text", "sbtl") }
        val quick = chapterTrack?.let { runCatching { readTextTrack(src, it) }.getOrNull() }.orEmpty()
        val marks = quick.ifEmpty { nero }
        return Result(normalize(marks, durationMs), durationMs)
    }

    private fun normalize(marks: List<Mark>, durationMs: Long?): List<Mark> =
        marks.filter { it.startMs >= 0 && (durationMs == null || it.startMs < durationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }

    private fun children(src: ByteSource, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        var guard = 0
        while (pos + 8 <= to && guard++ < 100_000) {
            var size = src.u32(pos)
            val type = String(src.readFully(pos + 4, 4), Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (pos + 16 > to) break
                size = src.u64(pos + 8)
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header || pos + size > to) break
            out += Box(type, pos, header, size)
            pos += size
        }
        return out
    }

    private fun readMvhd(src: ByteSource, b: Box): Long? {
        val version = src.u8(b.payload)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            timescale = src.u32(b.payload + 4 + 16)
            duration = src.u64(b.payload + 4 + 20)
        } else {
            timescale = src.u32(b.payload + 4 + 8)
            duration = src.u32(b.payload + 4 + 12)
        }
        return if (timescale > 0) duration * 1000 / timescale else null
    }

    private fun readChpl(src: ByteSource, b: Box): List<Mark> {
        var p = b.payload
        val version = src.u8(p)
        p += 4
        if (version != 0) p += 4
        if (p >= b.end) return emptyList()
        val count = src.u8(p)
        p += 1
        val out = ArrayList<Mark>()
        for (i in 0 until count) {
            if (p + 9 > b.end) break
            val start100ns = src.u64(p)
            val len = src.u8(p + 8)
            p += 9
            if (p + len > b.end) break
            val title = decodeText(src.readFully(p, len))
            p += len
            out += Mark(title, start100ns / 10_000)
        }
        return out
    }

    private fun readTrak(src: ByteSource, trak: Box): TrackInfo {
        val t = TrackInfo()
        for (b in children(src, trak.payload, trak.end)) {
            when (b.type) {
                "tkhd" -> {
                    val v = src.u8(b.payload)
                    t.id = if (v == 1) src.u32(b.payload + 4 + 16) else src.u32(b.payload + 4 + 8)
                }
                "tref" -> children(src, b.payload, b.end).firstOrNull { it.type == "chap" }?.let { chap ->
                    val n = ((chap.end - chap.payload) / 4).toInt().coerceAtMost(64)
                    t.chapterRefs = (0 until n).map { src.u32(chap.payload + it * 4L) }
                }
                "mdia" -> readMdia(src, b, t)
            }
        }
        return t
    }

    private fun readMdia(src: ByteSource, mdia: Box, t: TrackInfo) {
        for (b in children(src, mdia.payload, mdia.end)) {
            when (b.type) {
                "mdhd" -> {
                    val v = src.u8(b.payload)
                    t.timescale = if (v == 1) src.u32(b.payload + 4 + 16) else src.u32(b.payload + 4 + 8)
                }
                "hdlr" -> t.handler = String(src.readFully(b.payload + 8, 4), Charsets.ISO_8859_1)
                "minf" -> children(src, b.payload, b.end).firstOrNull { it.type == "stbl" }?.let { readStbl(src, it, t) }
            }
        }
    }

    private fun readStbl(src: ByteSource, stbl: Box, t: TrackInfo) {
        for (b in children(src, stbl.payload, stbl.end)) {
            when (b.type) {
                "stts" -> {
                    val n = src.u32(b.payload + 4).toInt().coerceIn(0, MAX_ENTRIES)
                    val data = src.readFully(b.payload + 8, n * 8)
                    t.stts = (0 until n).map { beLong(data, it * 8, 4) to beLong(data, it * 8 + 4, 4) }
                }
                "stsz" -> {
                    val fixed = src.u32(b.payload + 4)
                    val n = src.u32(b.payload + 8).toInt().coerceIn(0, MAX_ENTRIES)
                    t.sampleSizes = if (fixed != 0L) {
                        LongArray(n) { fixed }
                    } else {
                        val data = src.readFully(b.payload + 12, n * 4)
                        LongArray(n) { beLong(data, it * 4, 4) }
                    }
                }
                "stsc" -> {
                    val n = src.u32(b.payload + 4).toInt().coerceIn(0, MAX_ENTRIES)
                    val data = src.readFully(b.payload + 8, n * 12)
                    t.stsc = (0 until n).map {
                        Triple(beLong(data, it * 12, 4), beLong(data, it * 12 + 4, 4), beLong(data, it * 12 + 8, 4))
                    }
                }
                "stco" -> {
                    val n = src.u32(b.payload + 4).toInt().coerceIn(0, MAX_ENTRIES)
                    val data = src.readFully(b.payload + 8, n * 4)
                    t.chunkOffsets = LongArray(n) { beLong(data, it * 4, 4) }
                }
                "co64" -> {
                    val n = src.u32(b.payload + 4).toInt().coerceIn(0, MAX_ENTRIES)
                    val data = src.readFully(b.payload + 8, n * 8)
                    t.chunkOffsets = LongArray(n) { beLong(data, it * 8, 8) }
                }
            }
        }
    }

    private fun readTextTrack(src: ByteSource, t: TrackInfo): List<Mark> {
        if (t.timescale <= 0 || t.sampleSizes.isEmpty() || t.chunkOffsets.isEmpty()) return emptyList()
        val sampleCount = t.sampleSizes.size
        val starts = LongArray(sampleCount)
        var time = 0L
        var idx = 0
        for ((count, delta) in t.stts) {
            var c = count
            while (c > 0 && idx < sampleCount) {
                starts[idx++] = time
                time += delta
                c--
            }
        }
        while (idx < sampleCount) starts[idx++] = time

        val offsets = LongArray(sampleCount)
        var sample = 0
        val stsc = t.stsc.ifEmpty { listOf(Triple(1L, 1L, 1L)) }
        for (chunk in t.chunkOffsets.indices) {
            val chunkNo = chunk + 1L
            val entry = stsc.lastOrNull { it.first <= chunkNo } ?: stsc.first()
            var pos = t.chunkOffsets[chunk]
            var k = 0L
            while (k < entry.second && sample < sampleCount) {
                offsets[sample] = pos
                pos += t.sampleSizes[sample]
                sample++
                k++
            }
            if (sample >= sampleCount) break
        }

        val out = ArrayList<Mark>()
        for (i in 0 until sample) {
            val size = t.sampleSizes[i]
            var title = ""
            if (size >= 2 && offsets[i] + size <= src.size) {
                val len = src.u16(offsets[i])
                if (len in 1..(size - 2)) title = decodeText(src.readFully(offsets[i] + 2, len))
            }
            out += Mark(title, starts[i] * 1000 / t.timescale)
        }
        return out
    }
}
