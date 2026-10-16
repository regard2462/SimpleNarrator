package app.simplenarrator.tts

import android.speech.tts.TextToSpeech
import app.simplenarrator.parser.LanguageDetector
import app.simplenarrator.parser.Segmenter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reine Hilfsfunktionen für [SystemTtsService] und die TTS-Daten-Activities: Sprachzuordnung,
 * Sprechtempo, PCM-Aufbereitung und Aufteilung in Puffer. Ohne Android-Aufrufe, daher per JUnit prüfbar
 * (die TextToSpeech-Konstanten werden beim Übersetzen eingesetzt).
 */
object SystemTtsSupport {

    /** Eine Stimme, wie sie das System sieht. [code] ist die Sprache der App ("de"/"en"). */
    data class VoiceInfo(val code: String, val iso3: String, val iso3Country: String, val iso2Country: String, val name: String) {
        val tag: String get() = "$code-$iso2Country"
        /** Kennung für CHECK_TTS_DATA, z. B. "deu-DEU". */
        val dataId: String get() = "$iso3-$iso3Country"
    }

    val GERMAN = VoiceInfo("de", "deu", "DEU", "DE", "de-DE-x-simplenarrator-local")
    val ENGLISH = VoiceInfo("en", "eng", "USA", "US", "en-US-x-simplenarrator-local")
    val VOICES = listOf(GERMAN, ENGLISH)

    const val MIN_SPEED = 0.3f
    const val MAX_SPEED = 4.0f

    /** Pause zwischen zwei Sätzen derselben Anfrage. */
    const val SENTENCE_GAP_MS = 150

    /** Puffergröße, falls das System keine sinnvolle nennt. */
    private const val FALLBACK_BUFFER = 8192

    /** ISO-639-Code (zwei- oder dreistellig, auch "de-DE") auf die App-Sprache abbilden, sonst null. */
    fun appLang(language: String?): String? {
        val l = language?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
        return when (l) {
            "de", "deu", "ger" -> "de"
            "en", "eng" -> "en"
            else -> null
        }
    }

    fun voiceFor(appLang: String): VoiceInfo? = VOICES.firstOrNull { it.code == appLang }

    fun voiceByName(name: String?): VoiceInfo? =
        if (name.isNullOrBlank()) null else VOICES.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Antwort auf isLanguageAvailable: jede Region wird mit der vorhandenen Stimme bedient. Passt das Land zur
     * Stimme (DEU bzw. USA), heißt das LANG_COUNTRY_AVAILABLE, sonst LANG_AVAILABLE. Varianten gibt es nicht.
     */
    fun availability(language: String?, country: String?, @Suppress("UNUSED_PARAMETER") variant: String?): Int {
        val voice = appLang(language)?.let { voiceFor(it) } ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val c = country?.trim()?.uppercase().orEmpty()
        return if (c.isNotEmpty() && (c == voice.iso3Country || c == voice.iso2Country)) TextToSpeech.LANG_COUNTRY_AVAILABLE
        else TextToSpeech.LANG_AVAILABLE
    }

    /** Sprache einer Anfrage: Stimmenname vor Sprachcode, dann Erkennung am Text, dann die geladene Sprache. */
    fun resolveLang(voiceName: String?, language: String?, text: String, loaded: String?): String =
        voiceByName(voiceName)?.code ?: appLang(language) ?: LanguageDetector.detect(text) ?: appLang(loaded) ?: "de"

    /**
     * Antwort auf CHECK_TTS_DATA: ohne Wunschliste beide Stimmen. Mit Wunschliste (z. B. "eng-GBR") die eigenen
     * Stimmen der gewünschten Sprachen; Wünsche in anderen Sprachen gelten als nicht verfügbar.
     */
    fun checkVoices(requested: List<String>?): Pair<List<String>, List<String>> {
        if (requested.isNullOrEmpty()) return VOICES.map { it.dataId } to emptyList()
        val langs = requested.mapNotNull { appLang(it) }.toSet()
        val available = VOICES.filter { it.code in langs }.map { it.dataId }
        val unavailable = requested.filter { appLang(it) == null }
        return available to unavailable
    }

    /** Sprechtempo des Systems (100 = normal) als Faktor; ungültige Werte zählen als normal. */
    fun speedFactor(speechRate: Int): Float =
        if (speechRate <= 0) 1f else (speechRate / 100f).coerceIn(MIN_SPEED, MAX_SPEED)

    /**
     * Text in Sätze teilen wie beim Vorlesen. Kurze Eingaben, die der Segmentierer als nicht sprechbar
     * verwirft (TalkBack schickt etwa "5" oder "A"), werden als Ganzes gesprochen, solange sie Buchstaben
     * oder Ziffern enthalten.
     */
    fun sentences(text: String, lang: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        // Trennzeilen ("* * *") sind beim Vorlesen eine Szenenpause; andere Apps bekommen sie nicht gesprochen
        val parts = Segmenter.split(t, lang).filterNot { Segmenter.isSceneBreak(it) }
        if (parts.isNotEmpty()) return parts
        return if (t.any { it.isLetterOrDigit() }) listOf(t) else emptyList()
    }

    /** Ein Satz als 16-Bit-PCM bei [targetRate], im gewünschten Tempo, mit [gapMs] Stille dahinter. */
    fun toPcm16(samples: FloatArray, sourceRate: Int, targetRate: Int, speed: Float, gapMs: Int = 0): ByteArray {
        val resampled = if (sourceRate == targetRate) samples else Pcm.resample(samples, sourceRate, targetRate)
        val stretched = timeStretch(resampled, targetRate, speed)
        val pcm = Pcm.toInt16(stretched)
        if (gapMs <= 0) return pcm
        return pcm + ByteArray((gapMs.toLong() * targetRate / 1000).toInt() * 2)
    }

    /**
     * Gibt [data] in Stücken von höchstens [maxBufferSize] Bytes an [write] (immer ganze 16-Bit-Werte).
     * Liefert false, sobald [write] false liefert (abgebrochen oder Fehler).
     */
    inline fun writeChunked(data: ByteArray, maxBufferSize: Int, write: (ByteArray, Int, Int) -> Boolean): Boolean {
        val chunk = chunkSize(maxBufferSize)
        var off = 0
        while (off < data.size) {
            val len = minOf(chunk, data.size - off)
            if (!write(data, off, len)) return false
            off += len
        }
        return true
    }

    fun chunkSize(maxBufferSize: Int): Int {
        val size = if (maxBufferSize <= 0) FALLBACK_BUFFER else maxBufferSize
        return maxOf(2, size - size % 2)
    }

    /**
     * Tempo ändern ohne Tonhöhe (WSOLA): Fenster von 30 ms werden mit halber Überlappung neu gelegt, jedes an
     * der Stelle (±10 ms), die am besten an das vorige anschließt. Kostet wenige Millionen Multiplikationen je
     * Sekunde Audio. Sehr kurze Stücke und Tempo ≈ 1 bleiben unverändert.
     */
    fun timeStretch(input: FloatArray, sampleRate: Int, speed: Float): FloatArray {
        if (abs(speed - 1f) < 0.01f || sampleRate <= 0) return input
        val frame = (sampleRate * 30 / 1000).coerceAtLeast(64).let { it - it % 2 }
        val hop = frame / 2
        val tol = sampleRate * 10 / 1000
        if (input.size < frame * 2 + tol) return input

        val window = FloatArray(frame) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / frame)).toFloat() }
        val outLen = (input.size / speed).roundToInt()
        val out = FloatArray(outLen + frame)
        val weight = FloatArray(outLen + frame)
        var outPos = 0
        var prevStart = 0
        var k = 0
        while (outPos < outLen) {
            val nominal = (k.toDouble() * hop * speed).roundToInt()
            val start = if (k == 0) 0 else bestOffset(input, nominal, prevStart + hop, hop, tol, frame)
            if (start < 0 || start + frame > input.size) break
            for (j in 0 until frame) {
                out[outPos + j] += input[start + j] * window[j]
                weight[outPos + j] += window[j]
            }
            prevStart = start
            outPos += hop
            k++
        }
        val result = FloatArray(outLen)
        for (i in 0 until outLen) result[i] = if (weight[i] > 1e-6f) out[i] / weight[i] else 0f
        return result
    }

    /** Startpunkt nahe [nominal], dessen erste [len] Werte am besten zu input[natural…] passen; -1 am Ende. */
    private fun bestOffset(input: FloatArray, nominal: Int, natural: Int, len: Int, tol: Int, frame: Int): Int {
        val lo = maxOf(0, nominal - tol)
        val hi = minOf(input.size - frame, nominal + tol)
        if (lo > hi) return -1
        if (natural + len > input.size) return lo.coerceAtMost(hi)
        var best = lo
        var bestScore = Double.NEGATIVE_INFINITY
        var cand = lo
        while (cand <= hi) {
            var dot = 0.0
            var energy = 0.0
            var j = 0
            while (j < len) {
                val a = input[cand + j]
                dot += a * input[natural + j]
                energy += a * a
                j += 2
            }
            val score = if (energy > 1e-12) dot / sqrt(energy) else 0.0
            if (score > bestScore) { bestScore = score; best = cand }
            cand++
        }
        return best
    }
}
