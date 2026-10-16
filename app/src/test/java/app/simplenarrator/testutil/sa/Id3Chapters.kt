package app.simplenarrator.testutil.sa


/** Liest CHAP-Rahmen (ID3v2.3 und ID3v2.4) aus MP3-Dateien. */
object Id3Chapters {

    private const val MAX_TAG = 64 * 1024 * 1024

    fun read(src: ByteSource): List<Mark> {
        if (src.size < 10) return emptyList()
        val header = src.readFully(0, 10)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return emptyList()
        val major = header[3].toInt()
        if (major != 3 && major != 4) return emptyList()
        val flags = header[5].toInt()
        val tagSize = syncsafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG || 10L + tagSize > src.size) return emptyList()
        var data = src.readFully(10, tagSize)
        // Unsynchronisation auf Tag-Ebene (nur v2.3 setzt sie für das ganze Tag)
        if (flags and 0x80 != 0 && major == 3) data = undoUnsync(data)
        var pos = 0
        if (flags and 0x40 != 0) {
            pos = if (major == 4) syncsafe(data, 0) else (beLong(data, 0, 4).toInt() + 4)
        }
        val out = ArrayList<Mark>()
        for (frame in frames(data, pos, data.size, major)) {
            if (frame.id == "CHAP") parseChap(data, frame.start, frame.end, major)?.let { out += it }
        }
        return out.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    private class Frame(val id: String, val start: Int, val end: Int, val flags: Int)

    private fun frames(data: ByteArray, from: Int, to: Int, major: Int): List<Frame> {
        val out = ArrayList<Frame>()
        var p = from
        while (p + 10 <= to) {
            if (data[p] == 0.toByte()) break // Auffüllbytes
            val id = String(data, p, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (major == 4) syncsafe(data, p + 4) else beLong(data, p + 4, 4).toInt()
            val flags = ((data[p + 8].toInt() and 0xFF) shl 8) or (data[p + 9].toInt() and 0xFF)
            if (size < 0 || p + 10 + size > to) break
            out += Frame(id, p + 10, p + 10 + size, flags)
            p += 10 + size
        }
        return out
    }

    private fun parseChap(data: ByteArray, start: Int, end: Int, major: Int): Mark? {
        var p = start
        while (p < end && data[p] != 0.toByte()) p++ // Element-ID
        p++
        if (p + 16 > end) return null
        val startMs = beLong(data, p, 4)
        p += 16
        var title: String? = null
        for (sub in frames(data, p, end, major)) {
            if (sub.id == "TIT2" && sub.end > sub.start) {
                title = decodeId3Text(data.copyOfRange(sub.start, sub.end))
                break
            }
        }
        return Mark(title.orEmpty(), startMs)
    }

    internal fun decodeId3Text(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val enc = bytes[0].toInt()
        val body = bytes.copyOfRange(1, bytes.size)
        val s = when (enc) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> when {
                body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte() ->
                    String(body, 2, body.size - 2, Charsets.UTF_16LE)
                body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte() ->
                    String(body, 2, body.size - 2, Charsets.UTF_16BE)
                else -> String(body, Charsets.UTF_16LE)
            }
            2 -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_8)
        }
        return s.substringBefore('\u0000').trim()
    }

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun undoUnsync(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }
}
