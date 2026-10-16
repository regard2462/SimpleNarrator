package app.simplenarrator.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Hilfsfunktionen der System-Sprachausgabe: Sprachen, Tempo, PCM, Puffer. */
class SystemTtsSupportTest {

    @Test
    fun sprachcodes() {
        for (c in listOf("deu", "de", "ger", "DEU", "de-AT", "de_CH")) assertEquals(c, "de", SystemTtsSupport.appLang(c))
        for (c in listOf("eng", "en", "ENG", "en-GB")) assertEquals(c, "en", SystemTtsSupport.appLang(c))
        for (c in listOf("fra", "fr", "", null)) assertNull(SystemTtsSupport.appLang(c))
    }

    @Test
    fun verfuegbarkeit() {
        val s = SystemTtsSupport
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.availability("deu", "DEU", ""))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.availability("de", "DE", null))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.availability("deu", "DEU", "POSIX"))
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.availability("deu", "AUT", ""))
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.availability("deu", "", ""))
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.availability("deu", null, null))
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, s.availability("eng", "USA", ""))
        assertEquals(TextToSpeech.LANG_AVAILABLE, s.availability("eng", "GBR", ""))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, s.availability("fra", "FRA", ""))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, s.availability(null, null, null))
    }

    @Test
    fun stimmen() {
        val names = SystemTtsSupport.VOICES.map { it.name }
        assertEquals(2, names.toSet().size)
        assertEquals(listOf("de-DE", "en-US"), SystemTtsSupport.VOICES.map { it.tag })
        assertEquals(SystemTtsSupport.GERMAN, SystemTtsSupport.voiceByName(SystemTtsSupport.GERMAN.name))
        assertEquals(SystemTtsSupport.ENGLISH, SystemTtsSupport.voiceByName(SystemTtsSupport.ENGLISH.name.uppercase()))
        assertNull(SystemTtsSupport.voiceByName("de-DE-x-other-local"))
        assertNull(SystemTtsSupport.voiceByName(null))
        assertEquals("deu-DEU", SystemTtsSupport.GERMAN.dataId)
        assertEquals("eng-USA", SystemTtsSupport.ENGLISH.dataId)
    }

    @Test
    fun spracheEinerAnfrage() {
        val s = SystemTtsSupport
        assertEquals("en", s.resolveLang(s.ENGLISH.name, "deu", "Hallo", "deu"))
        assertEquals("de", s.resolveLang(null, "deu", "This is the English text", "eng"))
        assertEquals("en", s.resolveLang(null, "fra", "This is what they said to the man", "deu"))
        assertEquals("en", s.resolveLang(null, null, "ok", "eng"))
        assertEquals("de", s.resolveLang(null, null, "ok", null))
    }

    @Test
    fun datenPruefung() {
        assertEquals(listOf("deu-DEU", "eng-USA") to emptyList<String>(), SystemTtsSupport.checkVoices(null))
        assertEquals(listOf("deu-DEU", "eng-USA") to emptyList<String>(), SystemTtsSupport.checkVoices(emptyList()))
        assertEquals(listOf("eng-USA") to listOf("fra-FRA"), SystemTtsSupport.checkVoices(listOf("eng-GBR", "fra-FRA")))
        assertEquals(listOf("deu-DEU") to emptyList<String>(), SystemTtsSupport.checkVoices(listOf("deu-DEU")))
    }

    @Test
    fun tempo() {
        assertEquals(1f, SystemTtsSupport.speedFactor(100), 0f)
        assertEquals(2f, SystemTtsSupport.speedFactor(200), 0f)
        assertEquals(0.5f, SystemTtsSupport.speedFactor(50), 0f)
        assertEquals(SystemTtsSupport.MAX_SPEED, SystemTtsSupport.speedFactor(1000), 0f)
        assertEquals(SystemTtsSupport.MIN_SPEED, SystemTtsSupport.speedFactor(10), 0f)
        assertEquals(1f, SystemTtsSupport.speedFactor(0), 0f)
        assertEquals(1f, SystemTtsSupport.speedFactor(-3), 0f)
    }

    @Test
    fun saetze() {
        assertEquals(listOf("5"), SystemTtsSupport.sentences("5", "de"))
        assertEquals(listOf("A"), SystemTtsSupport.sentences(" A ", "en"))
        assertEquals(emptyList<String>(), SystemTtsSupport.sentences(".", "de"))
        assertEquals(emptyList<String>(), SystemTtsSupport.sentences("   ", "de"))
        val long = "Der erste Satz ist lang genug für sich. Der zweite Satz ist ebenfalls lang genug. Und ein dritter folgt hier."
        assertEquals(3, SystemTtsSupport.sentences(long, "de").size)
    }

    @Test
    fun puffer() {
        val data = ByteArray(10) { it.toByte() }
        val seen = mutableListOf<Pair<Int, Int>>()
        assertTrue(SystemTtsSupport.writeChunked(data, 4) { _, off, len -> seen += off to len; true })
        assertEquals(listOf(0 to 4, 4 to 4, 8 to 2), seen)

        seen.clear()
        assertTrue(SystemTtsSupport.writeChunked(data, 5) { _, off, len -> seen += off to len; true })
        assertEquals(listOf(0 to 4, 4 to 4, 8 to 2), seen)   // nur ganze 16-Bit-Werte

        seen.clear()
        assertFalse(SystemTtsSupport.writeChunked(data, 4) { _, off, len -> seen += off to len; off == 0 })
        assertEquals(listOf(0 to 4, 4 to 4), seen)            // Abbruch nach dem zweiten Stück

        assertEquals(8192, SystemTtsSupport.chunkSize(0))
        assertEquals(2, SystemTtsSupport.chunkSize(1))
        assertTrue(SystemTtsSupport.writeChunked(ByteArray(0), 4) { _, _, _ -> error("nie aufgerufen") })
    }

    @Test
    fun pcmMitUmtastungUndLuecke() {
        val samples = FloatArray(2205) { 0.5f }
        val pcm = SystemTtsSupport.toPcm16(samples, 22050, 24000, 1f, gapMs = 150)
        val resampled = Pcm.resample(samples, 22050, 24000).size
        assertTrue(abs(resampled - 2400) <= 1)
        assertEquals((resampled + 3600) * 2, pcm.size)
        val plain = SystemTtsSupport.toPcm16(floatArrayOf(1f, -1f), 24000, 24000, 1f)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x01, 0x80.toByte()), plain)
    }

    private fun sine(freq: Double, seconds: Double, rate: Int) =
        FloatArray((seconds * rate).toInt()) { i -> (0.5 * sin(2 * PI * freq * i / rate)).toFloat() }

    /** Nulldurchgänge pro Sekunde, ein Maß für die Tonhöhe. */
    private fun crossingsPerSecond(x: FloatArray, rate: Int, skip: Int): Double {
        var n = 0
        for (i in skip + 1 until x.size - skip) if ((x[i - 1] < 0f) != (x[i] < 0f)) n++
        return n.toDouble() * rate / (x.size - 2 * skip)
    }

    @Test
    fun zeitdehnungBehaeltTonhoehe() {
        val rate = 24000
        val input = sine(220.0, 2.0, rate)
        val inputRate = crossingsPerSecond(input, rate, 0)
        for (speed in listOf(0.5f, 0.8f, 1.5f, 2f, 3f)) {
            val out = SystemTtsSupport.timeStretch(input, rate, speed)
            val expected = input.size / speed
            assertTrue("Länge bei $speed: ${out.size} statt $expected", abs(out.size - expected) <= rate * 0.05)
            val outRate = crossingsPerSecond(out, rate, rate / 20)
            assertTrue("Tonhöhe bei $speed: $outRate statt $inputRate", abs(outRate - inputRate) / inputRate < 0.05)
            assertTrue("Pegel bei $speed", out.drop(rate / 20).dropLast(rate / 20).maxOf { abs(it) } in 0.4f..0.6f)
        }
    }

    @Test
    fun zeitdehnungLaesstNormaltempoUndKurzesUnveraendert() {
        val input = sine(220.0, 0.5, 24000)
        assertSame(input, SystemTtsSupport.timeStretch(input, 24000, 1f))
        assertSame(input, SystemTtsSupport.timeStretch(input, 24000, 1.005f))
        val short = FloatArray(100)
        assertSame(short, SystemTtsSupport.timeStretch(short, 24000, 2f))
    }
}
