package app.simplenarrator.export

import java.io.ByteArrayOutputStream
import java.util.Random

/** Baut kleine MP4-Dateien für Tests und liest Boxen, Chunk-Offsets und ilst-Einträge zurück. */
object Mp4TestKit {

    // ------------------------------------------------------------------ Bauen

    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xFFFFFFFFL)
    fun cat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArrayOutputStream()) { o, p -> o.write(p); o }.toByteArray()
    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = cat(*parts)
        return cat(u32(8L + body.size), type.toByteArray(Charsets.ISO_8859_1), body)
    }
    fun full(type: String, version: Int, flags: Int, vararg parts: ByteArray) =
        box(type, u32(((version.toLong()) shl 24) or flags.toLong()), *parts)

    class Synthetic(val bytes: ByteArray, val samples: List<ByteArray>, val durationMs: Long)

    /**
     * Eine Tonspur mit [chunks] × [perChunk] Proben verschiedener Größe. Zwischen den Chunks liegen Füllbytes,
     * damit falsch verschobene Offsets auffallen.
     */
    fun synthetic(
        moovFirst: Boolean,
        co64: Boolean = false,
        freeAfterMoov: Int = 0,
        largeMdat: Boolean = false,
        chunks: Int = 3,
        perChunk: Int = 4,
        durationMs: Long = 60_000,
        bogusOffset: Long? = null,
        udta: ByteArray? = null,
    ): Synthetic {
        val rnd = Random(7)
        val samples = List(chunks * perChunk) { i -> ByteArray(30 + i * 3).also { rnd.nextBytes(it) } }
        val gap = 7
        // mdat-Inhalt und Lage der Chunks darin
        val payload = ByteArrayOutputStream()
        val chunkRel = ArrayList<Long>()
        for (c in 0 until chunks) {
            payload.write(ByteArray(gap) { 0x55 })
            chunkRel += payload.size().toLong()
            for (s in 0 until perChunk) payload.write(samples[c * perChunk + s])
        }
        val mdatBody = payload.toByteArray()
        val mdat = if (largeMdat) cat(u32(1), "mdat".toByteArray(), u64(16L + mdatBody.size), mdatBody) else box("mdat", mdatBody)
        val mdatHeader = if (largeMdat) 16 else 8
        val ftyp = box("ftyp", "M4A ".toByteArray(), u32(0), "M4A ".toByteArray(), "mp42".toByteArray(), "isom".toByteArray())

        fun moov(mdatStart: Long): ByteArray {
            val offsets = chunkRel.map { mdatStart + mdatHeader + it }.toMutableList()
            if (bogusOffset != null) offsets[offsets.lastIndex] = bogusOffset
            val chunkBox = if (co64) full("co64", 0, 0, u32(offsets.size.toLong()), *offsets.map { u64(it) }.toTypedArray())
            else full("stco", 0, 0, u32(offsets.size.toLong()), *offsets.map { u32(it) }.toTypedArray())
            val stbl = box(
                "stbl",
                full("stsd", 0, 0, u32(1), box("mp4a", ByteArray(6), u16(1), ByteArray(8), u16(1), u16(16), u32(0), u32(24000L shl 16))),
                full("stts", 0, 0, u32(1), u32(samples.size.toLong()), u32(1024)),
                full("stsc", 0, 0, u32(1), u32(1), u32(perChunk.toLong()), u32(1)),
                full("stsz", 0, 0, u32(0), u32(samples.size.toLong()), *samples.map { u32(it.size.toLong()) }.toTypedArray()),
                chunkBox,
            )
            val minf = box("minf", full("smhd", 0, 0, u32(0)), box("dinf", full("dref", 0, 0, u32(1), full("url ", 0, 1))), stbl)
            val mdia = box(
                "mdia",
                full("mdhd", 0, 0, u32(0), u32(0), u32(24000), u32(durationMs * 24), u16(0x55C4), u16(0)),
                full("hdlr", 0, 0, u32(0), "soun".toByteArray(), ByteArray(12), "SoundHandler".toByteArray(), byteArrayOf(0)),
                minf,
            )
            val tkhd = full("tkhd", 0, 3, u32(0), u32(0), u32(1), u32(0), u32(durationMs), ByteArray(8), ByteArray(8), identity(), ByteArray(8))
            val mvhd = full("mvhd", 0, 0, u32(0), u32(0), u32(1000), u32(durationMs), u32(0x00010000), u16(0x0100), ByteArray(10), identity(), ByteArray(24), u32(2))
            return box("moov", mvhd, box("trak", tkhd, mdia), *(if (udta != null) arrayOf(udta) else emptyArray()))
        }

        val free = if (freeAfterMoov > 0) cat(u32(freeAfterMoov.toLong()), "free".toByteArray(), ByteArray(freeAfterMoov - 8)) else ByteArray(0)
        val bytes = if (moovFirst) {
            val size = moov(0).size
            cat(ftyp, moov(ftyp.size.toLong() + size + free.size), free, mdat)
        } else {
            cat(ftyp, mdat, moov(ftyp.size.toLong()))
        }
        return Synthetic(bytes, samples, durationMs)
    }

    private fun identity() = cat(u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0), u32(0), u32(0), u32(0x40000000))

    // ------------------------------------------------------------------ Lesen

    class Box(val type: String, val start: Int, val header: Int, val size: Int) {
        val payload get() = start + header
        val end get() = start + size
    }

    fun be(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    fun children(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = be(b, p, 4)
            var header = 8
            if (size == 1L) { size = be(b, p + 8, 8); header = 16 } else if (size == 0L) size = (to - p).toLong()
            require(size >= header && p + size <= to) { "broken box at $p" }
            out += Box(String(b, p + 4, 4, Charsets.ISO_8859_1), p, header, size.toInt())
            p += size.toInt()
        }
        require(p == to) { "trailing bytes at $p (to $to)" }
        return out
    }

    fun top(b: ByteArray) = children(b, 0, b.size)

    /** Erstes Kind auf einem Pfad wie "moov/udta/chpl". meta wird als FullBox behandelt. */
    fun find(b: ByteArray, path: String): Box? {
        var from = 0
        var to = b.size
        var found: Box? = null
        for (part in path.split('/')) {
            found = children(b, from, to).firstOrNull { it.type == part } ?: return null
            from = found.payload + if (found.type == "meta") 4 else 0
            to = found.end
        }
        return found
    }

    fun all(b: ByteArray, parent: Box, type: String): List<Box> = children(b, parent.payload, parent.end).filter { it.type == type }

    class TrackSamples(val handler: String, val samples: List<ByteArray>, val offsets: List<Long>)

    /** Probenbytes aller Spuren über stsc/stsz/stco|co64. */
    fun trackSamples(b: ByteArray): List<TrackSamples> {
        val moov = find(b, "moov")!!
        return all(b, moov, "trak").map { trak ->
            val mdia = all(b, trak, "mdia").single()
            val hdlr = all(b, mdia, "hdlr").single()
            val handler = String(b, hdlr.payload + 8, 4, Charsets.ISO_8859_1)
            val stbl = all(b, all(b, mdia, "minf").single(), "stbl").single()
            val stsz = all(b, stbl, "stsz").single()
            val fixed = be(b, stsz.payload + 4, 4)
            val count = be(b, stsz.payload + 8, 4).toInt()
            val sizes = List(count) { if (fixed != 0L) fixed.toInt() else be(b, stsz.payload + 12 + it * 4, 4).toInt() }
            val stsc = all(b, stbl, "stsc").single()
            val stscEntries = List(be(b, stsc.payload + 4, 4).toInt()) { i -> be(b, stsc.payload + 8 + i * 12, 4) to be(b, stsc.payload + 12 + i * 12, 4) }
            val co = (all(b, stbl, "stco") + all(b, stbl, "co64")).single()
            val w = if (co.type == "co64") 8 else 4
            val offsets = List(be(b, co.payload + 4, 4).toInt()) { be(b, co.payload + 8 + it * w, w) }
            val out = ArrayList<ByteArray>()
            var s = 0
            for ((ci, off) in offsets.withIndex()) {
                val per = stscEntries.last { it.first <= ci + 1 }.second
                var p = off
                for (k in 0 until per) {
                    if (s >= count) break
                    out += b.copyOfRange(p.toInt(), p.toInt() + sizes[s]); p += sizes[s]; s++
                }
            }
            TrackSamples(handler, out, offsets)
        }
    }

    /** ilst-Einträge: Schlüssel → (Datentyp, Wert). */
    fun ilst(b: ByteArray): Map<String, Pair<Int, ByteArray>> {
        val ilst = find(b, "moov/udta/meta/ilst") ?: return emptyMap()
        return children(b, ilst.payload, ilst.end).associate { item ->
            val data = children(b, item.payload, item.end).first { it.type == "data" }
            item.type to (be(b, data.payload, 4).toInt() to b.copyOfRange(data.payload + 8, data.end))
        }
    }

    fun text(b: ByteArray, key: String): String? = ilst(b)[key]?.let { String(it.second, Charsets.UTF_8) }
}
