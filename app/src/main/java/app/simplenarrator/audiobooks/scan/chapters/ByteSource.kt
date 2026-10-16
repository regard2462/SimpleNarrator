package app.simplenarrator.audiobooks.scan.chapters

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Wahlfreier Lesezugriff auf eine Datei. */
interface ByteSource {
    val size: Long

    /** Liest bis zu [len] Bytes ab [pos]. Liefert die Anzahl gelesener Bytes, 0 am Dateiende. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    fun readFully(pos: Long, len: Int): ByteArray {
        require(len >= 0)
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = read(pos + done, out, done, len - done)
            if (n <= 0) throw IOException("Dateiende bei ${pos + done}")
            done += n
        }
        return out
    }

    fun u8(pos: Long): Int = readFully(pos, 1)[0].toInt() and 0xFF
    fun u16(pos: Long): Int = readFully(pos, 2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
    fun u32(pos: Long): Long = beLong(readFully(pos, 4), 0, 4)
    fun u64(pos: Long): Long = beLong(readFully(pos, 8), 0, 8)
}

fun beLong(b: ByteArray, off: Int, len: Int): Long {
    var v = 0L
    for (i in 0 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
    return v
}

fun leLong(b: ByteArray, off: Int, len: Int): Long {
    var v = 0L
    for (i in len - 1 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
    return v
}

class ArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return 0
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }
}

class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return 0
        val n = channel.read(ByteBuffer.wrap(buf, off, len), pos)
        return if (n < 0) 0 else n
    }
}

/** Dekodiert Text in UTF-8 oder UTF-16 mit Byte-Order-Mark, ohne Nullzeichen am Ende. */
fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).trimEnd('\u0000').trim()
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE).trimEnd('\u0000').trim()
    }
    return String(bytes, Charsets.UTF_8).trimEnd('\u0000').trim()
}
