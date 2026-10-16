package app.simplenarrator.tts

import android.util.Log
import app.simplenarrator.data.AppSettings
import app.simplenarrator.data.SettingsStore
import app.simplenarrator.speech.Run
import app.simplenarrator.speech.SpeechRules
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Wählt pro Satz die passende Stimme (Sprache, Erzähler oder wörtliche Rede), lädt Modelle bei Bedarf und
 * serialisiert alle Synthese-Aufrufe auf einen Thread.
 */
class TtsManager(private val models: ModelManager, private val settings: SettingsStore) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa-tts") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    /** Geladene Modelle, zuletzt benutztes zuletzt. */
    private val engines = LinkedHashMap<String, TtsEngine>(4, 0.75f, true)

    private val _loading = MutableStateFlow<String?>(null)
    /** Name der Stimme, die gerade geladen wird (die Oberfläche macht daraus „Lade Stimme …“), sonst null. */
    val loading: StateFlow<String?> = _loading

    class NoModelException : Exception("No voice model installed")

    /** Eine konkrete Stimme: Paket, Sprecher und bei gemischten Kokoro-Stimmen die eigene voices-Datei. */
    data class Voice(val pack: VoicePack, val sid: Int, val voicesFile: File? = null)

    /**
     * Stimme für [lang]. Ist die gewählte nicht installiert, nimmt sie ein anderes installiertes Paket derselben
     * Sprache (empfohlene zuerst) und erst danach eines der anderen Sprache, damit weitergelesen wird.
     */
    fun voiceFor(lang: String, dialogue: Boolean, s: AppSettings): Voice? {
        val installed = models.installed.value
        fun chosen(l: String): Voice? {
            val (packId, sid, blendId) = when {
                dialogue && l == "de" && s.dialoguePackDe.isNotEmpty() -> Triple(s.dialoguePackDe, s.dialogueSidDe, "")
                dialogue && l == "en" && s.dialoguePackEn.isNotEmpty() -> Triple(s.dialoguePackEn, s.dialogueSidEn, "")
                l == "de" -> Triple(s.packDe, s.sidDe, s.blendDe)
                else -> Triple(s.packEn, s.sidEn, s.blendEn)
            }
            val pack = VoicePacks.byId(packId)?.takeIf { it.id in installed && it.supports(l) } ?: return null
            val blend = Blends.parse(s.blends).firstOrNull { it.id == blendId && it.packId == pack.id }
            val file = blend?.let { Blends.file(models, pack, it) }?.takeIf { it.isFile }
            // Wörtliche Rede ohne eigene Wahl: nächster Sprecher desselben Pakets, falls es mehrere gibt
            if (dialogue && !s.dialogueVoiceChosen(l) && pack.voices.size > 1) {
                val i = pack.voices.indexOfFirst { it.sid == sid }
                return Voice(pack, pack.voices[(i + 1).mod(pack.voices.size)].sid)
            }
            return Voice(pack, if (file != null) blend.slot else sid, file)
        }
        fun anyFor(l: String): Voice? = VoicePacks.forLang(l).filter { it.id in installed }
            .sortedByDescending { it.recommended }.firstOrNull()?.let { Voice(it, 0) }
        val other = if (lang == "de") "en" else "de"
        return chosen(lang) ?: anyFor(lang) ?: chosen(other) ?: anyFor(other)
    }

    private var rulesFor = ""
    private var rulesCache = SpeechRules.EMPTY

    /** Regelwerk zum Wörterbuch-Text, zwischengespeichert, solange sich der Text nicht ändert. */
    @Synchronized
    fun rules(raw: String): SpeechRules {
        if (raw != rulesFor) { rulesCache = SpeechRules.parse(raw); rulesFor = raw }
        return rulesCache
    }

    /**
     * Ein Satz für Vorlesen und Export: Regeln (Wörterbuch, Pausen), Textaufbereitung nach der Sprache des
     * Textes, wörtliche Rede mit eigener Stimme ([runs] aus [app.simplenarrator.speech.Dialogue]) und die
     * Pause nach Satz oder Absatz.
     */
    suspend fun renderSentence(text: String, lang: String, runs: List<Run>?, paragraphEnd: Boolean, s: AppSettings): TtsAudio =
        SpeechPipeline.render(
            text, lang, runs, paragraphEnd, rules(s.lexicon),
            SpeechOptions(
                normalize = s.normalizeText, sentencePauseMs = s.sentencePauseMs, paragraphPauseMs = s.paragraphPauseMs,
                dialogueVoice = s.dialogueVoice, espeak = usesEspeak(lang, s),
            ),
        ) { t, l, dialogue -> synthesizeRaw(t, l, dialogue, s) }

    /** Liest die Erzählstimme für [lang] über espeak-ng? Alle Engines außer Supertonic tun das. */
    private fun usesEspeak(lang: String, s: AppSettings): Boolean = voiceFor(lang, false, s)?.pack?.engine != Engine.SUPERTONIC

    /** Ein Stück Text ohne Pausen danach (System-Sprachausgabe für andere Apps). */
    suspend fun synthesize(text: String, lang: String): TtsAudio {
        val s = settings.current()
        return SpeechPipeline.render(
            text, lang, null, false, rules(s.lexicon),
            SpeechOptions(normalize = s.normalizeText, sentencePauseMs = 0, paragraphPauseMs = 0, dialogueVoice = false, espeak = usesEspeak(lang, s)),
        ) { t, l, dialogue -> synthesizeRaw(t, l, dialogue, s) }
    }

    /** Synthese ohne Aufbereitung; [dialogue] wählt die Stimme für wörtliche Rede. */
    suspend fun synthesizeRaw(text: String, lang: String, dialogue: Boolean, s: AppSettings): TtsAudio = mutex.withLock {
        val voiceLang = if (s.autoLang) lang else (if (voiceFor("de", false, s)?.pack?.supports("de") == true) "de" else "en")
        val voice = voiceFor(voiceLang, dialogue && s.dialogueVoice, s) ?: throw NoModelException()
        withContext(dispatcher) {
            val engine = engineFor(voice, s)
            // Supertonic bekommt die Sprache des Textes; Modelle mit nur einer Sprache ignorieren sie.
            val speakLang = if (voice.pack.supports(lang)) lang else voice.pack.langs.first()
            engine.synthesize(text, s.voiceSpeed, voice.sid, speakLang)
        }
    }

    private fun engineFor(v: Voice, s: AppSettings): TtsEngine {
        val key = "${v.pack.id}|${v.voicesFile?.name.orEmpty()}|${s.threads}"
        engines[key]?.let { return it }
        // Erzähler und wörtliche Rede einer Sprache sollen gemeinsam passen; mehr nur auf Wunsch.
        val max = if (s.keepBothLoaded) 4 else 2
        while (engines.size >= max) {
            val oldest = engines.keys.first()
            engines.remove(oldest)?.release()
        }
        val files = models.resolve(v.pack, v.voicesFile) ?: throw NoModelException()
        _loading.value = v.pack.name
        Log.i("TtsManager", "Loading $key")
        try {
            return SherpaTtsEngine(v.pack, files, s.threads, key).also { engines[key] = it }
        } finally {
            _loading.value = null
        }
    }

    suspend fun releaseAll() = mutex.withLock {
        withContext(dispatcher) { engines.values.forEach { it.release() }; engines.clear() }
    }
}
