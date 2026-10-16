package app.simplenarrator.tts

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Mischt zwei Kokoro-Stimmen. Eine sherpa-onnx-voices.bin ist rohes float32 (little endian) der Form
 * [Sprecher, 510, 1, 256]; die Größe pro Sprecher wird aus Dateigröße / Sprecherzahl bestimmt, damit auch
 * andere Stil-Dimensionen gehen.
 */
object VoiceBlend {

    /**
     * Schreibt eine Kopie von [voicesBin] nach [out], in der Sprecher [slot] durch
     * weightA · A + (1 − weightA) · B ersetzt ist. Alle anderen Sprecher bleiben bytegleich.
     * Nur die beiden Quellstimmen liegen im Speicher, der Rest wird blockweise kopiert.
     * [out] darf gleich [voicesBin] sein; geschrieben wird erst in eine temporäre Datei.
     */
    fun blend(voicesBin: File, numSpeakers: Int, a: Int, b: Int, weightA: Float, slot: Int, out: File) {
        require(numSpeakers > 0) { "numSpeakers must be positive" }
        require(a in 0 until numSpeakers && b in 0 until numSpeakers && slot in 0 until numSpeakers) { "Speaker index out of range" }
        require(weightA in 0f..1f) { "weightA must be between 0 and 1" }
        val size = voicesBin.length()
        if (size == 0L || size % (numSpeakers * 4L) != 0L) throw IOException("voices.bin size $size does not fit $numSpeakers speakers")
        val per = size / numSpeakers
        if (per > Int.MAX_VALUE) throw IOException("Speaker block too large")

        val tmp = File(out.absoluteFile.parentFile, out.name + ".part")
        try {
            RandomAccessFile(voicesBin, "r").use { raf ->
                val va = readSpeaker(raf, a, per.toInt())
                val vb = readSpeaker(raf, b, per.toInt())
                val mixed = ByteBuffer.allocate(per.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                val wb = 1f - weightA
                for (i in 0 until per.toInt() / 4) mixed.putFloat(weightA * va.getFloat(i * 4) + wb * vb.getFloat(i * 4))

                tmp.outputStream().buffered(1 shl 16).use { o ->
                    copyRange(raf, 0, slot * per, o)
                    o.write(mixed.array())
                    copyRange(raf, (slot + 1) * per, size, o)
                }
            }
            try {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun readSpeaker(raf: RandomAccessFile, index: Int, per: Int): ByteBuffer {
        val arr = ByteArray(per)
        raf.seek(index.toLong() * per)
        raf.readFully(arr)
        return ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun copyRange(raf: RandomAccessFile, from: Long, to: Long, o: java.io.OutputStream) {
        val buf = ByteArray(1 shl 16)
        raf.seek(from)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n <= 0) throw IOException("Unexpected end of voices.bin")
            o.write(buf, 0, n)
            left -= n
        }
    }
}
