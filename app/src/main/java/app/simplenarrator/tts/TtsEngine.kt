package app.simplenarrator.tts

class TtsAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationSec: Float get() = samples.size.toFloat() / sampleRate
}

interface TtsEngine {
    val key: String
    /** [lang] ist die Sprache des Textes; mehrsprachige Modelle (Supertonic) wählen damit ihre Aussprache. */
    fun synthesize(text: String, speed: Float, sid: Int, lang: String): TtsAudio
    fun release()
}
