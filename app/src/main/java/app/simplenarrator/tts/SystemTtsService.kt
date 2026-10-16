package app.simplenarrator.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import app.simplenarrator.SimpleNarratorApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Stellt die Stimmen der App anderen Apps als System-Sprachausgabe zur Verfügung (Readest, Librera, TalkBack …).
 *
 * Deutsch und Englisch in jeder Region, je eine Stimme (de-DE, en-US), die gewählte Stimme der App.
 * Wörterbuch und Normalisierung wendet TtsManager.synthesize an. Längere Texte werden in Sätze geteilt und
 * Satz für Satz gestreamt, damit das Vorlesen früh beginnt und ein Stopp sofort greift.
 * Das Sprechtempo des Systems wird per Zeitdehnung (WSOLA) umgesetzt, die Tonhöhe bleibt unbeachtet.
 */
class SystemTtsService : TextToSpeechService() {
    companion object {
        private const val TAG = "SystemTtsService"
    }

    /** Zuletzt geladene Sprache (ISO 639-2, ISO 3166 alpha-3, Variante). */
    @Volatile private var loaded = arrayOf(SystemTtsSupport.GERMAN.iso3, SystemTtsSupport.GERMAN.iso3Country, "")
    @Volatile private var stopped = false
    @Volatile private var requestJob: Job? = null

    /** Synthese läuft außerhalb des Anfrage-Coroutines, damit ein Stopp nicht auf den laufenden Satz warten muss. */
    private val synthScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val container get() = (application as SimpleNarratorApp).container

    override fun onDestroy() {
        synthScope.cancel()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        SystemTtsSupport.availability(lang, country, variant)

    override fun onGetLanguage(): Array<String> = loaded.copyOf()

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = SystemTtsSupport.availability(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            SystemTtsSupport.appLang(lang)?.let { SystemTtsSupport.voiceFor(it) }?.let { select(it) }
        }
        return result
    }

    override fun onGetVoices(): List<Voice> = SystemTtsSupport.VOICES.map { v ->
        Voice(v.name, Locale.forLanguageTag(v.tag), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        SystemTtsSupport.appLang(lang)?.let { SystemTtsSupport.voiceFor(it) }?.name

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (SystemTtsSupport.voiceByName(voiceName) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        val v = SystemTtsSupport.voiceByName(voiceName) ?: return TextToSpeech.ERROR
        select(v)
        return TextToSpeech.SUCCESS
    }

    private fun select(v: SystemTtsSupport.VoiceInfo) {
        loaded = arrayOf(v.iso3, v.iso3Country, "")
    }

    override fun onStop() {
        stopped = true
        requestJob?.cancel()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val text = request.charSequenceText?.toString().orEmpty()
        val lang = SystemTtsSupport.resolveLang(request.voiceName, request.language, text, loaded[0])
        val sentences = SystemTtsSupport.sentences(text, lang)
        val speed = SystemTtsSupport.speedFactor(request.speechRate)
        val tts = container.tts
        try {
            runBlocking {
                requestJob = coroutineContext.job
                if (stopped) return@runBlocking
                if (sentences.isEmpty()) {
                    // Nichts Sprechbares (nur Satzzeichen): leere, aber gültige Ausgabe
                    if (callback.start(SpeechPipeline.DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) callback.done()
                    return@runBlocking
                }
                var rate = 0
                for ((i, sentence) in sentences.withIndex()) {
                    if (stopped) return@runBlocking
                    val audio = synthScope.async { tts.synthesize(sentence, lang) }.await()
                    if (stopped) return@runBlocking
                    if (rate == 0) {
                        rate = audio.sampleRate
                        if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return@runBlocking
                    }
                    val gap = if (i < sentences.lastIndex) SystemTtsSupport.SENTENCE_GAP_MS else 0
                    val pcm = SystemTtsSupport.toPcm16(audio.samples, audio.sampleRate, rate, speed, gap)
                    val ok = SystemTtsSupport.writeChunked(pcm, callback.maxBufferSize) { buf, off, len ->
                        !stopped && callback.audioAvailable(buf, off, len) == TextToSpeech.SUCCESS
                    }
                    if (!ok) return@runBlocking
                }
                callback.done()
            }
        } catch (e: CancellationException) {
            // onStop(): nichts weiter melden, das System hat die Anfrage schon beendet
        } catch (e: TtsManager.NoModelException) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
        } catch (e: Exception) {
            Log.e(TAG, "Synthese fehlgeschlagen", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        } finally {
            requestJob = null
        }
    }
}
