package app.simplenarrator.audiobooks.scan.chapters

import app.simplenarrator.audiobooks.data.Mark
import java.io.ByteArrayOutputStream

/**
 * Kapitel aus Vorbis-Kommentaren (CHAPTER001=00:00:00.000, CHAPTER001NAME=Titel),
 * wie sie in Ogg-Vorbis-, Opus- und FLAC-Dateien vorkommen.
 */
object VorbisChapters {

    private const val MAX_SCAN = 8 * 1024 * 1024

    fun readOgg(src: ByteSource): List<Mark> {
        val packet = oggCommentPacket(src) ?: return emptyList()
        val body = when {
            packet.size >= 7 && packet[0] == 3.toByte() && String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> 7
            packet.size >= 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> 8
            else -> return emptyList()
        }
        return fromComments(parseComments(packet, body))
    }

    fun readFlac(src: ByteSource): List<Mark> {
        if (src.size < 8 || String(src.readFully(0, 4), Charsets.ISO_8859_1) != "fLaC") return emptyList()
        var p = 4L
        var guard = 0
        while (p + 4 <= src.size && guard++ < 128) {
            val h = src.readFully(p, 4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = beLong(h, 1, 3).toInt()
            if (type == 4 && len in 0..MAX_SCAN && p + 4 + len <= src.size) {
                return fromComments(parseComments(src.readFully(p + 4, len), 0))
            }
            p += 4 + len
            if (last) break
        }
        return emptyList()
    }

    /** Setzt das zweite Paket des ersten logischen Ogg-Stroms zusammen (den Kommentarkopf). */
    private fun oggCommentPacket(src: ByteSource): ByteArray? {
        var p = 0L
        var serial = -1L
        var packetIndex = 0
        val current = ByteArrayOutputStream()
        while (p + 27 <= src.size && p < MAX_SCAN) {
            val h = src.readFully(p, 27)
            if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
            val pageSerial = leLong(h, 14, 4)
            val nSegs = h[26].toInt() and 0xFF
            val table = src.readFully(p + 27, nSegs)
            var dataPos = p + 27 + nSegs
            val mine = serial == -1L || pageSerial == serial
            if (serial == -1L) serial = pageSerial
            for (i in 0 until nSegs) {
                val segLen = table[i].toInt() and 0xFF
                if (mine) {
                    if (packetIndex == 1) current.write(src.readFully(dataPos, segLen))
                    if (segLen < 255) {
                        if (packetIndex == 1) return current.toByteArray()
                        packetIndex++
                    }
                }
                dataPos += segLen
            }
            p = dataPos
        }
        return null
    }

    internal fun parseComments(b: ByteArray, start: Int): List<Pair<String, String>> {
        var p = start
        if (p + 4 > b.size) return emptyList()
        val vendorLen = leLong(b, p, 4).toInt()
        p += 4 + vendorLen
        if (vendorLen < 0 || p + 4 > b.size) return emptyList()
        val count = leLong(b, p, 4).toInt()
        p += 4
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until count.coerceAtMost(10_000)) {
            if (p + 4 > b.size) break
            val len = leLong(b, p, 4).toInt()
            p += 4
            if (len < 0 || p + len > b.size) break
            val s = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = s.indexOf('=')
            if (eq > 0) out += s.substring(0, eq).uppercase() to s.substring(eq + 1)
        }
        return out
    }

    private val KEY = Regex("""^CHAPTER(\d+)(NAME)?$""")

    internal fun fromComments(comments: List<Pair<String, String>>): List<Mark> {
        val times = HashMap<Int, Long>()
        val names = HashMap<Int, String>()
        for ((k, v) in comments) {
            val m = KEY.matchEntire(k) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (m.groupValues[2].isEmpty()) parseTime(v)?.let { times[n] = it } else names[n] = v.trim()
        }
        return times.entries.sortedBy { it.value }.map { (n, t) -> Mark(names[n].orEmpty(), t) }.distinctBy { it.startMs }
    }

    /** "HH:MM:SS.mmm", "MM:SS.mmm" oder "HH:MM:SS". */
    internal fun parseTime(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val v = part.toDoubleOrNull() ?: return null
            total = total * 60 + v
        }
        return (total * 1000).toLong()
    }
}
