package app.simplenarrator.tts

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Pcm {
    /** Float [-1,1] → 16-bit little-endian. */
    fun toInt16(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    fun silence(seconds: Float, sampleRate: Int): ByteArray = ByteArray((seconds * sampleRate).toInt() * 2)

    /** Schwelle für "still" (etwa -42 dBFS als Effektivwert eines 10-ms-Fensters). */
    const val SILENCE_RMS = 0.008f

    /**
     * Stille, die das Modell selbst vor und hinter den Satz legt, abschneiden. Sonst kommt sie zu jeder
     * eingestellten Pause hinzu, und jeder Satzwechsel wird spürbar länger als gewollt. Vor dem ersten und
     * hinter dem letzten hörbaren Fenster bleibt etwas Rand für leise Laute ("s", "f"), die Schnittkanten
     * werden kurz eingeblendet. Ohne hörbares Fenster bleibt das Audio, wie es ist.
     */
    fun trimSilence(samples: FloatArray, rate: Int, leadMs: Int = 30, tailMs: Int = 60): FloatArray {
        val win = (rate / 100).coerceAtLeast(1)
        val windows = (samples.size + win - 1) / win
        fun loud(w: Int): Boolean {
            val from = w * win
            val to = minOf(samples.size, from + win)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            return Math.sqrt(sum / (to - from)) > SILENCE_RMS
        }
        val first = (0 until windows).firstOrNull { loud(it) } ?: return samples
        val last = (windows - 1 downTo first).first { loud(it) }
        val start = maxOf(0, first * win - leadMs * rate / 1000)
        val end = minOf(samples.size, (last + 1) * win + tailMs * rate / 1000)
        if (start == 0 && end == samples.size) return samples
        val out = samples.copyOfRange(start, end)
        val fade = minOf(rate / 200, out.size / 2)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            if (start > 0) out[i] *= g
            if (end < samples.size) out[out.size - 1 - i] *= g
        }
        return out
    }

    /** Lineare Umtastung (z. B. Piper 22,05 kHz → 24 kHz), damit gemischte Stimmen in eine Datei passen. */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val ratio = from.toDouble() / to
        val n = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i * ratio
            val j = pos.toInt().coerceAtMost(samples.lastIndex)
            val frac = (pos - j).toFloat()
            val a = samples[j]; val b = samples[minOf(j + 1, samples.lastIndex)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

object WavWriter {
    fun write(file: File, audio: TtsAudio) {
        val pcm = Pcm.toInt16(audio.samples)
        file.outputStream().buffered().use { out ->
            writeHeader(out, pcm.size, audio.sampleRate)
            out.write(pcm)
        }
    }

    /** Liest eine selbst geschriebene WAV-Datei (16-bit mono, 44-Byte-Kopf) zurück. */
    fun read(file: File): TtsAudio {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(24)
        val n = (bytes.size - 44) / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = bb.getShort(44 + i * 2) / 32768f
        return TtsAudio(out, rate)
    }

    /** Roh-PCM-Datei (16-bit mono) in eine WAV-Datei umwandeln. */
    fun fromPcmFile(pcm: File, sampleRate: Int, out: File) {
        out.outputStream().buffered().use { o ->
            writeHeader(o, pcm.length().toInt(), sampleRate)
            pcm.inputStream().buffered().use { it.copyTo(o) }
        }
    }

    private fun writeHeader(out: OutputStream, dataLen: Int, sampleRate: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        out.write(h.array())
    }
}
