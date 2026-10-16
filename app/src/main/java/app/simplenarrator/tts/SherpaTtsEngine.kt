package app.simplenarrator.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Kokoro, Kitten, Supertonic oder VITS/Piper über sherpa-onnx (ONNX Runtime + espeak-ng, komplett offline).
 * Ein Objekt hält ein geladenes Modell; Sprecher und Sprache kommen je Aufruf, damit ein mehrsprachiges
 * Modell nicht doppelt im Speicher liegt. Nicht threadsicher, TtsManager serialisiert die Aufrufe.
 */
class SherpaTtsEngine(
    private val pack: VoicePack,
    files: ModelFiles,
    threads: Int,
    override val key: String,
) : TtsEngine {

    private val tts: OfflineTts = OfflineTts(config = OfflineTtsConfig(model = modelConfig(files, threads), ruleFsts = "", maxNumSentences = 1))

    private fun modelConfig(f: ModelFiles, threads: Int): OfflineTtsModelConfig = when (pack.engine) {
        Engine.VITS -> OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = f.model, tokens = f.tokens, dataDir = f.espeakData),
            numThreads = threads, debug = false, provider = "cpu",
        )
        Engine.KOKORO -> OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = f.model, voices = f.voices, tokens = f.tokens, dataDir = f.espeakData,
                lexicon = f.lexicon, lang = pack.espeakLang,
            ),
            numThreads = threads, debug = false, provider = "cpu",
        )
        Engine.KITTEN -> OfflineTtsModelConfig(
            kitten = OfflineTtsKittenModelConfig(model = f.model, voices = f.voices, tokens = f.tokens, dataDir = f.espeakData),
            numThreads = threads, debug = false, provider = "cpu",
        )
        Engine.SUPERTONIC -> OfflineTtsModelConfig(
            supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = f.supertonic.getValue("duration_predictor"),
                textEncoder = f.supertonic.getValue("text_encoder"),
                vectorEstimator = f.supertonic.getValue("vector_estimator"),
                vocoder = f.supertonic.getValue("vocoder"),
                ttsJson = f.supertonic.getValue("tts_json"),
                unicodeIndexer = f.supertonic.getValue("unicode_indexer"),
                voiceStyle = f.voices,
            ),
            numThreads = threads, debug = false, provider = "cpu",
        )
    }

    override fun synthesize(text: String, speed: Float, sid: Int, lang: String): TtsAudio {
        val audio = when (pack.engine) {
            // Supertonic ist mehrsprachig: Sprache über „extra“, 8 Schritte wie in der sherpa-onnx-Dokumentation
            Engine.SUPERTONIC -> tts.generateWithConfig(
                text, GenerationConfig(speed = speed, sid = sid, numSteps = SUPERTONIC_STEPS, extra = mapOf("lang" to lang)),
            )
            else -> tts.generate(text = text, sid = sid, speed = speed)
        }
        return TtsAudio(audio.samples, audio.sampleRate)
    }

    override fun release() = tts.release()

    companion object {
        const val SUPERTONIC_STEPS = 8
    }
}
