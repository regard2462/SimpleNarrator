package app.simplenarrator.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

/** Schrift der Leseansicht. */
enum class ReaderFont { SERIF, SANS, MONO }

/** Farben der Leseansicht; APP folgt dem Farbschema der App. */
enum class ReaderTheme { APP, LIGHT, SEPIA, DARK, BLACK }

data class AppSettings(
    // ---- Stimmen
    val packDe: String = "vits-piper-de_DE-thorsten-high",
    val packEn: String = "kokoro-en-v0_19",
    val sidDe: Int = 0,
    val sidEn: Int = 0,
    /** Gemischte Kokoro-Stimme statt sidDe/sidEn (Kennung aus [blends]), leer = keine. */
    val blendDe: String = "",
    val blendEn: String = "",
    /** Gespeicherte Mischungen als JSON-Liste (siehe tts/Blends.kt). */
    val blends: String = "",
    val voiceSpeed: Float = 1.0f,       // Synthese-Tempo (wirkt auch auf Export)
    val autoLang: Boolean = true,       // Sprache pro Absatz automatisch erkennen
    val keepBothLoaded: Boolean = false, // mehrere Stimmen gleichzeitig im RAM
    val threads: Int = 2,
    // ---- Wörtliche Rede
    val dialogueVoice: Boolean = false,
    val dialoguePackDe: String = "",
    val dialogueSidDe: Int = 0,
    val dialoguePackEn: String = "",
    val dialogueSidEn: Int = 0,
    // ---- Aufbereitung und Pausen
    val lexicon: String = "",           // Regeln, siehe speech/SpeechRules.kt
    val normalizeText: Boolean = true,  // Zahlen, Daten, Abkürzungen für die Sprachausgabe ausschreiben
    // Seit 0.5 ohne die Stille der Modelle gemessen, darum höher als früher (200/500)
    val sentencePauseMs: Int = 350,
    val paragraphPauseMs: Int = 750,
    val chapterPauseMs: Int = 1500,     // nur im Export zwischen Kapiteln
    val stopAfterSentence: Boolean = false, // Lernmodus: nach jedem Satz anhalten
    // ---- Vorlesen
    val wordHighlight: Boolean = true,
    val sleepFade: Boolean = true,
    val shakeToExtend: Boolean = false,
    val rewindAfterPause: Boolean = true,
    val cacheAudio: Boolean = true,
    val cacheLimitMb: Int = 400,
    // ---- Leseansicht
    val readerFont: ReaderFont = ReaderFont.SERIF,
    val readerFontSize: Int = 18,
    val readerLineHeight: Float = 1.65f,
    val readerMargin: Int = 22,
    val readerTheme: ReaderTheme = ReaderTheme.APP,
    // ---- Export
    val exportBitrate: Int = 64,        // kbit/s für M4A/MP3
    val exportFormat: String = "MP3",
    val exportSingle: Boolean = false,
    val exportSubtitles: Boolean = false,
) {
    fun dialogueVoiceChosen(lang: String) = if (lang == "de") dialoguePackDe.isNotEmpty() else dialoguePackEn.isNotEmpty()
}

class SettingsStore(private val context: Context) {
    private companion object {
        /** Standardpausen bis 0.4, als die Modellstille noch dazukam. */
        const val OLD_SENTENCE_PAUSE = 200
        const val OLD_PARAGRAPH_PAUSE = 500
    }

    private object K {
        val PACK_DE = stringPreferencesKey("pack_de")
        val PACK_EN = stringPreferencesKey("pack_en")
        val SID_DE = intPreferencesKey("sid_de")
        val SID_EN = intPreferencesKey("sid_en")
        val BLEND_DE = stringPreferencesKey("blend_de")
        val BLEND_EN = stringPreferencesKey("blend_en")
        val BLENDS = stringPreferencesKey("blends")
        val SPEED = floatPreferencesKey("voice_speed")
        val AUTO_LANG = booleanPreferencesKey("auto_lang")
        val KEEP_BOTH = booleanPreferencesKey("keep_both")
        val THREADS = intPreferencesKey("threads")
        val DIALOGUE = booleanPreferencesKey("dialogue_voice")
        val DIALOGUE_PACK_DE = stringPreferencesKey("dialogue_pack_de")
        val DIALOGUE_SID_DE = intPreferencesKey("dialogue_sid_de")
        val DIALOGUE_PACK_EN = stringPreferencesKey("dialogue_pack_en")
        val DIALOGUE_SID_EN = intPreferencesKey("dialogue_sid_en")
        val LEXICON = stringPreferencesKey("lexicon")
        val NORMALIZE = booleanPreferencesKey("normalize_text")
        val PAUSE_SENTENCE = intPreferencesKey("pause_sentence")
        val PAUSE_PARAGRAPH = intPreferencesKey("pause_paragraph")
        val PAUSE_CHAPTER = intPreferencesKey("pause_chapter")
        /** 2: Pausen ohne Modellstille (0.5). Fehlt der Schlüssel, gelten alte Standardwerte als nie geändert. */
        val PAUSE_SCHEME = intPreferencesKey("pause_scheme")
        val STOP_AFTER = booleanPreferencesKey("stop_after_sentence")
        val WORD_HIGHLIGHT = booleanPreferencesKey("word_highlight")
        val SLEEP_FADE = booleanPreferencesKey("sleep_fade")
        val SHAKE = booleanPreferencesKey("shake_extend")
        val REWIND = booleanPreferencesKey("rewind_after_pause")
        val CACHE = booleanPreferencesKey("cache_audio")
        val CACHE_LIMIT = intPreferencesKey("cache_limit_mb")
        val R_FONT = stringPreferencesKey("reader_font")
        val R_SIZE = intPreferencesKey("reader_font_size")
        val R_LINE = floatPreferencesKey("reader_line_height")
        val R_MARGIN = intPreferencesKey("reader_margin")
        val R_THEME = stringPreferencesKey("reader_theme")
        val BITRATE = intPreferencesKey("export_bitrate")
        val EXPORT_FORMAT = stringPreferencesKey("export_format")
        val EXPORT_SINGLE = booleanPreferencesKey("export_single")
        val EXPORT_SUBS = booleanPreferencesKey("export_subtitles")
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E = v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: d

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            packDe = p[K.PACK_DE] ?: d.packDe,
            packEn = p[K.PACK_EN] ?: d.packEn,
            sidDe = p[K.SID_DE] ?: d.sidDe,
            sidEn = p[K.SID_EN] ?: d.sidEn,
            blendDe = p[K.BLEND_DE] ?: d.blendDe,
            blendEn = p[K.BLEND_EN] ?: d.blendEn,
            blends = p[K.BLENDS] ?: d.blends,
            voiceSpeed = p[K.SPEED] ?: d.voiceSpeed,
            autoLang = p[K.AUTO_LANG] ?: d.autoLang,
            keepBothLoaded = p[K.KEEP_BOTH] ?: d.keepBothLoaded,
            threads = p[K.THREADS] ?: d.threads,
            dialogueVoice = p[K.DIALOGUE] ?: d.dialogueVoice,
            dialoguePackDe = p[K.DIALOGUE_PACK_DE] ?: d.dialoguePackDe,
            dialogueSidDe = p[K.DIALOGUE_SID_DE] ?: d.dialogueSidDe,
            dialoguePackEn = p[K.DIALOGUE_PACK_EN] ?: d.dialoguePackEn,
            dialogueSidEn = p[K.DIALOGUE_SID_EN] ?: d.dialogueSidEn,
            lexicon = p[K.LEXICON] ?: d.lexicon,
            normalizeText = p[K.NORMALIZE] ?: d.normalizeText,
            sentencePauseMs = p[K.PAUSE_SENTENCE]?.takeUnless { it == OLD_SENTENCE_PAUSE && p[K.PAUSE_SCHEME] == null } ?: d.sentencePauseMs,
            paragraphPauseMs = p[K.PAUSE_PARAGRAPH]?.takeUnless { it == OLD_PARAGRAPH_PAUSE && p[K.PAUSE_SCHEME] == null } ?: d.paragraphPauseMs,
            chapterPauseMs = p[K.PAUSE_CHAPTER] ?: d.chapterPauseMs,
            stopAfterSentence = p[K.STOP_AFTER] ?: d.stopAfterSentence,
            wordHighlight = p[K.WORD_HIGHLIGHT] ?: d.wordHighlight,
            sleepFade = p[K.SLEEP_FADE] ?: d.sleepFade,
            shakeToExtend = p[K.SHAKE] ?: d.shakeToExtend,
            rewindAfterPause = p[K.REWIND] ?: d.rewindAfterPause,
            cacheAudio = p[K.CACHE] ?: d.cacheAudio,
            cacheLimitMb = p[K.CACHE_LIMIT] ?: d.cacheLimitMb,
            readerFont = enumOr(p[K.R_FONT], d.readerFont),
            readerFontSize = p[K.R_SIZE] ?: d.readerFontSize,
            readerLineHeight = p[K.R_LINE] ?: d.readerLineHeight,
            readerMargin = p[K.R_MARGIN] ?: d.readerMargin,
            readerTheme = enumOr(p[K.R_THEME], d.readerTheme),
            exportBitrate = p[K.BITRATE] ?: d.exportBitrate,
            exportFormat = p[K.EXPORT_FORMAT] ?: d.exportFormat,
            exportSingle = p[K.EXPORT_SINGLE] ?: d.exportSingle,
            exportSubtitles = p[K.EXPORT_SUBS] ?: d.exportSubtitles,
        )
    }

    private fun write(p: MutablePreferences, n: AppSettings) {
        p[K.PACK_DE] = n.packDe; p[K.PACK_EN] = n.packEn
        p[K.SID_DE] = n.sidDe; p[K.SID_EN] = n.sidEn
        p[K.BLEND_DE] = n.blendDe; p[K.BLEND_EN] = n.blendEn; p[K.BLENDS] = n.blends
        p[K.SPEED] = n.voiceSpeed; p[K.AUTO_LANG] = n.autoLang
        p[K.KEEP_BOTH] = n.keepBothLoaded; p[K.THREADS] = n.threads
        p[K.DIALOGUE] = n.dialogueVoice
        p[K.DIALOGUE_PACK_DE] = n.dialoguePackDe; p[K.DIALOGUE_SID_DE] = n.dialogueSidDe
        p[K.DIALOGUE_PACK_EN] = n.dialoguePackEn; p[K.DIALOGUE_SID_EN] = n.dialogueSidEn
        p[K.LEXICON] = n.lexicon; p[K.NORMALIZE] = n.normalizeText
        p[K.PAUSE_SENTENCE] = n.sentencePauseMs; p[K.PAUSE_PARAGRAPH] = n.paragraphPauseMs; p[K.PAUSE_SCHEME] = 2; p[K.PAUSE_CHAPTER] = n.chapterPauseMs
        p[K.STOP_AFTER] = n.stopAfterSentence
        p[K.WORD_HIGHLIGHT] = n.wordHighlight; p[K.SLEEP_FADE] = n.sleepFade; p[K.SHAKE] = n.shakeToExtend
        p[K.REWIND] = n.rewindAfterPause; p[K.CACHE] = n.cacheAudio; p[K.CACHE_LIMIT] = n.cacheLimitMb
        p[K.R_FONT] = n.readerFont.name; p[K.R_SIZE] = n.readerFontSize; p[K.R_LINE] = n.readerLineHeight
        p[K.R_MARGIN] = n.readerMargin; p[K.R_THEME] = n.readerTheme.name
        p[K.BITRATE] = n.exportBitrate; p[K.EXPORT_FORMAT] = n.exportFormat
        p[K.EXPORT_SINGLE] = n.exportSingle; p[K.EXPORT_SUBS] = n.exportSubtitles
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { read(it) }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p -> write(p, block(read(p))) }
    }
}
