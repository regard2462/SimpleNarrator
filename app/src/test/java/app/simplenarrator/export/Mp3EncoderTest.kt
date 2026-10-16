package app.simplenarrator.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** MP3-Kodierung über jump3r auf der JVM: Rahmenkette, Bitrate, Dauer und Rückdekodierung eines 440-Hz-Sinus. */
class Mp3EncoderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sine22050() = checkSine(22050)

    @Test
    fun sine24000() = checkSine(24000)

    private fun checkSine(rate: Int) {
        val seconds = 3
        val pcm = sinePcm(rate, seconds, 440.0)
        val out = File(tmp.root, "sine$rate.mp3")
        Mp3Encoder.encode(pcm, rate, out, 64)
        val bytes = out.readBytes()
        val info = MpegFrames.scan(bytes)
        assertEquals(rate, info.sampleRate)
        assertEquals(576, info.samplesPerFrame)          // MPEG-2 Layer III
        assertEquals(setOf(64), info.bitrates)
        assertTrue("LAME Info tag in the first frame", info.infoTag)
        assertEquals("frames must reach the end of the file", bytes.size, info.endOffset)
        val audioFrames = info.frames - (if (info.infoTag) 1 else 0)
        val duration = audioFrames * 576.0 / rate
        assertTrue("duration $duration", duration in 3.0..3.2)
        val kbps = (bytes.size - info.firstFrameOffset) * 8.0 / (info.frames * 576.0 / rate) / 1000
        assertTrue("bitrate $kbps", abs(kbps - 64) < 2)

        // Zurückdekodieren (jump3r-Kommandozeile im Test) und Frequenz über Nulldurchgänge prüfen
        val wav = File(tmp.root, "back$rate.wav")
        de.sciss.jump3r.Main().run(arrayOf("--quiet", "--decode", out.absolutePath, wav.absolutePath))
        val samples = wavSamples(wav)
        assertTrue("decoded ${samples.size} samples", abs(samples.size - rate * seconds) < rate / 10)
        val mid = samples.copyOfRange(rate / 2, rate * 5 / 2)
        var crossings = 0
        for (i in 1 until mid.size) if ((mid[i - 1] < 0) != (mid[i] < 0)) crossings++
        val freq = crossings / 2.0 / (mid.size.toDouble() / rate)
        assertTrue("frequency $freq", abs(freq - 440) < 5)
        val rms = sqrt(mid.sumOf { it.toDouble() * it } / mid.size)
        val expected = 0.5 * 32767 / sqrt(2.0)
        assertTrue("rms $rms vs $expected", abs(rms - expected) / expected < 0.15)
    }

    /** Misst die Geschwindigkeit mit einem sprachähnlichen Signal; zum Vergleich auch jump3rs Kommandozeile mit WAV. */
    @Test
    fun speed() {
        val rate = 24000
        val seconds = 120
        val pcm = speechLikePcm(rate, seconds)
        val out = File(tmp.root, "speed.mp3")
        Mp3Encoder.encode(pcm, rate, out, 64) // Aufwärmen
        val t0 = System.nanoTime()
        Mp3Encoder.encode(pcm, rate, out, 64)
        val s = (System.nanoTime() - t0) / 1e9
        assertEquals(setOf(64), MpegFrames.scan(out.readBytes()).bitrates)

        val wav = File(tmp.root, "speed.wav")
        app.simplenarrator.tts.WavWriter.fromPcmFile(pcm, rate, wav)
        val cli = File(tmp.root, "speed-cli.mp3")
        val t1 = System.nanoTime()
        de.sciss.jump3r.Main().run(arrayOf("--quiet", "-b", "64", "-m", "m", wav.absolutePath, cli.absolutePath))
        val c = (System.nanoTime() - t1) / 1e9
        println(String.format(Locale.ROOT, "MP3 speed (streaming, q5): %d s audio in %.2f s = %.0fx realtime, %d bytes", seconds, s, seconds / s, out.length()))
        println(String.format(Locale.ROOT, "MP3 speed (jump3r CLI, default q): %d s audio in %.2f s = %.0fx realtime, %d bytes", seconds, c, seconds / c, cli.length()))
    }

    /** Grundton mit Vibrato und Obertönen, silbenartig moduliert, dazu Rauschen; fest geseedet. */
    private fun speechLikePcm(rate: Int, seconds: Int): File {
        val n = rate * seconds
        val rnd = java.util.Random(42)
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f0 = 140 + 40 * sin(2 * PI * 0.7 * t)
            phase += 2 * PI * f0 / rate
            var v = 0.0
            for (h in 1..8) v += sin(h * phase) / h
            val env = 0.5 + 0.5 * sin(2 * PI * 4 * t)
            v = 0.25 * v * env + 0.05 * rnd.nextGaussian() * (1 - env)
            buf.putShort((v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
        }
        return File(tmp.root, "speech$rate-$seconds.pcm").apply { writeBytes(buf.array()) }
    }

    private fun sinePcm(rate: Int, seconds: Int, freq: Double): File {
        val n = rate * seconds
        val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) buf.putShort((0.5 * 32767 * sin(2 * PI * freq * i / rate)).toInt().toShort())
        return File(tmp.root, "sine$rate-$seconds.pcm").apply { writeBytes(buf.array()) }
    }

    private fun wavSamples(wav: File): ShortArray {
        val b = ByteBuffer.wrap(wav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(b.array(), pos, 4, Charsets.US_ASCII)
            val len = b.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(len, b.limit() - pos - 8) / 2
                return ShortArray(n) { b.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + len + (len and 1)
        }
        error("no data chunk")
    }
}

/** Minimaler MPEG-Audio-Rahmenleser für Tests. */
object MpegFrames {
    class Info(
        val frames: Int, val sampleRate: Int, val samplesPerFrame: Int, val bitrates: Set<Int>,
        val firstFrameOffset: Int, val endOffset: Int, val infoTag: Boolean,
    )

    private val BR_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    fun scan(b: ByteArray): Info {
        var pos = 0
        if (b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            pos = 10 + (((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F))
        }
        val first = pos
        var frames = 0
        var rate = 0
        var spf = 0
        val brs = HashSet<Int>()
        var info = false
        while (pos + 4 <= b.size) {
            val h1 = b[pos + 1].toInt() and 0xFF
            val h2 = b[pos + 2].toInt() and 0xFF
            if (b[pos] != 0xFF.toByte() || h1 and 0xE0 != 0xE0) break
            val version = (h1 shr 3) and 3          // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
            val layer = (h1 shr 1) and 3            // 1 = Layer III
            require(layer == 1) { "not layer III at $pos" }
            val brIndex = h2 shr 4
            val srIndex = (h2 shr 2) and 3
            val pad = (h2 shr 1) and 1
            val br = (if (version == 3) BR_V1 else BR_V2)[brIndex]
            val sr = when (version) {
                3 -> intArrayOf(44100, 48000, 32000)[srIndex]
                2 -> intArrayOf(22050, 24000, 16000)[srIndex]
                else -> intArrayOf(11025, 12000, 8000)[srIndex]
            }
            spf = if (version == 3) 1152 else 576
            val len = (if (version == 3) 144 else 72) * br * 1000 / sr + pad
            if (frames == 0) {
                val head = String(b, pos, minOf(64, b.size - pos), Charsets.ISO_8859_1)
                info = head.contains("Info") || head.contains("Xing")
            }
            if (!(frames == 0 && info)) brs += br
            rate = sr
            frames++
            pos += len
        }
        return Info(frames, rate, spf, brs, first, minOf(pos, b.size), info)
    }
}
