package app.simplenarrator.tts

import androidx.annotation.StringRes
import app.simplenarrator.R

/** Eine Stimme in einem Paket. Ohne Namen zeigt die Oberfläche „Stimme <sid+1>“. */
data class VoiceInfo(val name: String, val sid: Int)

data class PackFile(val url: String, val dest: String)

/** Modellarten, die sherpa-onnx auf dem Gerät ausführt. */
enum class Engine { VITS, KOKORO, KITTEN, SUPERTONIC }

/**
 * Ein Modellpaket für sherpa-onnx im Ordner models/<dirName>/.
 *  - VITS (Piper): <name>.onnx, tokens.txt, espeak-ng-data/
 *  - KOKORO, KITTEN: *.onnx, voices.bin, tokens.txt, espeak-ng-data/ (sonst aus einem anderen Paket)
 *  - SUPERTONIC: duration_predictor, text_encoder, vector_estimator, vocoder (*.onnx), tts.json,
 *    unicode_indexer.bin, voice.bin; ein Modell für viele Sprachen, die Sprache wird je Aufruf übergeben.
 * Dateinamen der .onnx-Modelle werden im Ordner gesucht, damit fp32-, fp16- und int8-Fassungen gleich gehen.
 */
data class VoicePack(
    val id: String,
    val name: String,
    /** Sprachen, für die das Paket in den Einstellungen erscheint (de, en). */
    val langs: List<String>,
    val engine: Engine,
    val sizeMb: Int,
    val voices: List<VoiceInfo>,
    /** Sprachhinweis für espeak-ng (de, en-us, en-gb); bei Supertonic unbenutzt. */
    val espeakLang: String = "",
    val dirName: String = id,
    val archiveUrl: String? = null,           // tar.bz2 mit Ordner <dirName>/ (sherpa-onnx-Releases)
    val files: List<PackFile> = emptyList(),   // alternativ: Einzeldateien
    /** Dateiname einer voices.npz aus einem kokoro-onnx-Export; wird nach dem Download aufbereitet. */
    val convertNpz: String? = null,
    /** Hinweis für die Einstellungen, in der Sprache der Oberfläche. */
    @StringRes val note: Int,
    /** Empfohlenes Paket für eine Sprache (wird oben gezeigt). */
    val recommended: Boolean = false,
) {
    fun supports(lang: String) = lang in langs
}

object VoicePacks {
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    private fun numbered(n: Int) = (0 until n).map { VoiceInfo("", it) }

    private fun piper(id: String, lang: String, espeak: String, sizeMb: Int, @StringRes note: Int, voices: List<VoiceInfo>, recommended: Boolean = false) =
        VoicePack(
            id = id, name = pipeName(id), langs = listOf(lang), engine = Engine.VITS, sizeMb = sizeMb, voices = voices,
            espeakLang = espeak, archiveUrl = "$SHERPA/$id.tar.bz2", note = note, recommended = recommended,
        )

    /** „vits-piper-de_DE-thorsten-medium-int8“ → „Thorsten (Piper, medium, int8)“ */
    private fun pipeName(id: String): String {
        val parts = id.removePrefix("vits-piper-").split('-')
        val speaker = parts.getOrElse(1) { "" }.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val rest = parts.drop(2).joinToString(", ")
        return "$speaker (Piper, $rest)"
    }

    val all: List<VoicePack> = listOf(
        // ---------------------------------------------------------------- mehrsprachig
        VoicePack(
            id = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11", name = "Supertonic 3 (int8)",
            langs = listOf("de", "en"), engine = Engine.SUPERTONIC, sizeMb = 122, voices = numbered(10),
            archiveUrl = "$SHERPA/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            note = R.string.pack_note_supertonic, recommended = true,
        ),

        // ---------------------------------------------------------------- Deutsch
        piper("vits-piper-de_DE-thorsten-medium-int8", "de", "de", 19, R.string.pack_note_thorsten_small, listOf(VoiceInfo("Thorsten", 0)), recommended = true),
        piper("vits-piper-de_DE-thorsten-high-int8", "de", "de", 33, R.string.pack_note_thorsten_high, listOf(VoiceInfo("Thorsten", 0))),
        VoicePack(
            id = "vits-piper-de_DE-thorsten-high", name = "Thorsten (Piper, high)", langs = listOf("de"), engine = Engine.VITS,
            sizeMb = 110, voices = listOf(VoiceInfo("Thorsten", 0)), espeakLang = "de",
            archiveUrl = "$SHERPA/vits-piper-de_DE-thorsten-high.tar.bz2", note = R.string.pack_note_thorsten_full,
        ),
        piper(
            "vits-piper-de_DE-thorsten_emotional-medium-int8", "de", "de", 22, R.string.pack_note_thorsten_emotional,
            listOf("amused", "angry", "disgusted", "drunk", "neutral", "sleepy", "surprised", "whisper").mapIndexed { i, n -> VoiceInfo(n, i) },
        ),
        piper("vits-piper-de_DE-kerstin-low-int8", "de", "de", 20, R.string.pack_note_piper_female, listOf(VoiceInfo("Kerstin", 0))),
        piper("vits-piper-de_DE-ramona-low-int8", "de", "de", 20, R.string.pack_note_piper_female, listOf(VoiceInfo("Ramona", 0))),
        piper("vits-piper-de_DE-eva_k-x_low-int8", "de", "de", 12, R.string.pack_note_piper_female_small, listOf(VoiceInfo("Eva K", 0))),
        piper("vits-piper-de_DE-karlsson-low-int8", "de", "de", 20, R.string.pack_note_piper_male, listOf(VoiceInfo("Karlsson", 0))),
        piper("vits-piper-de_DE-pavoque-low-int8", "de", "de", 20, R.string.pack_note_piper_male, listOf(VoiceInfo("Pavoque", 0))),
        VoicePack(
            id = "kokoro-de-martin", name = "Kokoro Martin (kikiri-tts, community)", langs = listOf("de"), engine = Engine.KOKORO,
            sizeMb = 330, voices = listOf(VoiceInfo("Martin", 0)), espeakLang = "de",
            files = listOf(
                PackFile("https://huggingface.co/Godelaune/Kokoro-82M-ONNX-German-Martin/resolve/main/kokoro-martin.onnx", "model.onnx"),
                PackFile("https://huggingface.co/Godelaune/Kokoro-82M-ONNX-German-Martin/resolve/main/voices-martin.npz", "voices-martin.npz"),
            ),
            convertNpz = "voices-martin.npz", note = R.string.pack_note_martin,
        ),
        VoicePack(
            id = "kokoro-de-thorsten", name = "Kokoro Thorsten (ONNX export)", langs = listOf("de"), engine = Engine.KOKORO,
            sizeMb = 330, voices = listOf(VoiceInfo("Thorsten", 0)), espeakLang = "de",
            convertNpz = "voices-thorsten.npz", note = R.string.pack_note_kokoro_thorsten,
        ),

        // ---------------------------------------------------------------- Englisch
        VoicePack(
            id = "kokoro-int8-multi-lang-v1_0", name = "Kokoro 1.0 (int8)", langs = listOf("en"), engine = Engine.KOKORO,
            sizeMb = 126, espeakLang = "en-us", archiveUrl = "$SHERPA/kokoro-int8-multi-lang-v1_0.tar.bz2",
            voices = KOKORO_V1_VOICES, note = R.string.pack_note_kokoro_multi, recommended = true,
        ),
        VoicePack(
            id = "kokoro-multi-lang-v1_0", name = "Kokoro 1.0", langs = listOf("en"), engine = Engine.KOKORO,
            sizeMb = 333, espeakLang = "en-us", archiveUrl = "$SHERPA/kokoro-multi-lang-v1_0.tar.bz2",
            voices = KOKORO_V1_VOICES, note = R.string.pack_note_kokoro_multi_full,
        ),
        VoicePack(
            id = "kokoro-en-v0_19", name = "Kokoro 0.19", langs = listOf("en"), engine = Engine.KOKORO,
            sizeMb = 304, espeakLang = "en-us", archiveUrl = "$SHERPA/kokoro-en-v0_19.tar.bz2",
            voices = listOf(
                VoiceInfo("af", 0), VoiceInfo("af_bella", 1), VoiceInfo("af_nicole", 2), VoiceInfo("af_sarah", 3),
                VoiceInfo("af_sky", 4), VoiceInfo("am_adam", 5), VoiceInfo("am_michael", 6), VoiceInfo("bf_emma", 7),
                VoiceInfo("bf_isabella", 8), VoiceInfo("bm_george", 9), VoiceInfo("bm_lewis", 10),
            ),
            note = R.string.pack_note_kokoro_en,
        ),
        piper("vits-piper-en_US-lessac-medium-int8", "en", "en-us", 19, R.string.pack_note_piper_en, listOf(VoiceInfo("Lessac", 0))),
        piper("vits-piper-en_US-ryan-high-int8", "en", "en-us", 32, R.string.pack_note_piper_en, listOf(VoiceInfo("Ryan", 0))),
        piper("vits-piper-en_US-amy-medium-int8", "en", "en-us", 20, R.string.pack_note_piper_en, listOf(VoiceInfo("Amy", 0))),
        piper("vits-piper-en_GB-alan-medium-int8", "en", "en-gb", 20, R.string.pack_note_piper_en_gb, listOf(VoiceInfo("Alan", 0))),
        piper("vits-piper-en_GB-cori-high-int8", "en", "en-gb", 33, R.string.pack_note_piper_en_gb, listOf(VoiceInfo("Cori", 0))),
        VoicePack(
            id = "kitten-nano-en-v0_8-int8", name = "Kitten nano 0.8 (int8)", langs = listOf("en"), engine = Engine.KITTEN,
            sizeMb = 29, espeakLang = "en-us", archiveUrl = "$SHERPA/kitten-nano-en-v0_8-int8.tar.bz2",
            voices = numbered(8), note = R.string.pack_note_kitten,
        ),
    )

    fun byId(id: String): VoicePack? = all.firstOrNull { it.id == id }
    fun forLang(lang: String): List<VoicePack> = all.filter { it.supports(lang) }
}

/** Englische Stimmen von Kokoro 1.0 (53 Sprecher); IDs laut sherpa-onnx-Dokumentation, Seite „kokoro“. */
private val KOKORO_V1_VOICES = listOf(
    VoiceInfo("af_alloy", 0), VoiceInfo("af_aoede", 1), VoiceInfo("af_bella", 2), VoiceInfo("af_heart", 3),
    VoiceInfo("af_jessica", 4), VoiceInfo("af_kore", 5), VoiceInfo("af_nicole", 6), VoiceInfo("af_nova", 7),
    VoiceInfo("af_river", 8), VoiceInfo("af_sarah", 9), VoiceInfo("af_sky", 10), VoiceInfo("am_adam", 11),
    VoiceInfo("am_echo", 12), VoiceInfo("am_eric", 13), VoiceInfo("am_fenrir", 14), VoiceInfo("am_liam", 15),
    VoiceInfo("am_michael", 16), VoiceInfo("am_onyx", 17), VoiceInfo("am_puck", 18), VoiceInfo("am_santa", 19),
    VoiceInfo("bf_alice", 20), VoiceInfo("bf_emma", 21), VoiceInfo("bf_isabella", 22), VoiceInfo("bf_lily", 23),
    VoiceInfo("bm_daniel", 24), VoiceInfo("bm_fable", 25), VoiceInfo("bm_george", 26), VoiceInfo("bm_lewis", 27),
)
