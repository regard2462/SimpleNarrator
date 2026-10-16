package app.simplenarrator.tts

import app.simplenarrator.parser.Segmenter
import app.simplenarrator.speech.Normalizer
import app.simplenarrator.speech.Run
import app.simplenarrator.speech.Segment
import app.simplenarrator.speech.SpeechRules

/**
 * Einstellungen für [SpeechPipeline.render].
 * [sentencePauseMs] folgt jedem Satz, [paragraphPauseMs] ersetzt sie am Absatzende.
 * [dialogueVoice]: wörtliche Rede mit der Dialogstimme lesen (synth bekommt dialogue = true).
 */
data class SpeechOptions(
    val normalize: Boolean = true,
    val sentencePauseMs: Int = 250,
    val paragraphPauseMs: Int = 700,
    val dialogueVoice: Boolean = false,
    /** Die Erzählstimme liest über espeak-ng (alle außer Supertonic), siehe [app.simplenarrator.speech.Speakable]. */
    val espeak: Boolean = true,
)

/**
 * Ein Satz vom Buchtext zum fertigen Audio, ohne Android-Abhängigkeiten und damit testbar:
 * Regeln anwenden (Wörterbuch, Pausen), Sprechstücke normalisieren (nach den Regeln, wie bisher),
 * Erzähl- und Dialogteile getrennt synthetisieren, Pausen als Stille einfügen und alles bei der
 * Abtastrate des ersten Sprechstücks zusammensetzen.
 *
 * Die Stille, die ein Modell selbst um jedes Stück legt, wird abgeschnitten; so gelten die eingestellten
 * Pausen genau und summieren sich nicht mit der Modellstille. Auslassungspunkte mitten im Satz werden zu
 * einer Zögerpause (espeak-ng liest sie ohne jede Pause), eine Trennzeile wie "* * *" zu einer Szenenpause.
 */
object SpeechPipeline {
    /** Abtastrate, falls gar nichts gesprochen wird (Kokoro). */
    const val DEFAULT_SAMPLE_RATE = 24000

    /** Mindestlänge des Ergebnisses, wenn vom Satz nach den Regeln nichts Sprechbares bleibt. */
    const val EMPTY_SILENCE_MS = 100

    /** Pause zwischen zwei Sprechstücken eines Satzes (Erzähl- und Dialogstimme), etwa ein Komma. */
    const val JOIN_GAP_MS = 120

    /** Zögerpause für Auslassungspunkte mitten im Satz ("Ich … ich weiß nicht"). */
    const val ELLIPSIS_PAUSE_MS = 350

    /** Szenenpause für eine Trennzeile ("* * *", "———"), zusätzlich zur Pause am Absatzende. */
    const val SCENE_BREAK_MS = 1200

    /** Auslassungspunkte: "…", "..." oder ". . .". */
    private val ELLIPSIS = Regex("""\s*(?:…|\.\s?\.\s?\.)\.*\s*""")

    /**
     * Text an Auslassungspunkten teilen, hinter denen der Satz weitergeht. Das Stück davor bekommt ein Komma
     * für die Satzmelodie einer Unterbrechung; Punkte am Anfang oder Ende bleiben, wie sie sind.
     */
    internal fun splitAtEllipsis(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (m in ELLIPSIS.findAll(text)) {
            val before = text.substring(start, m.range.first)
            val after = text.substring(m.range.last + 1)
            if (before.none { it.isLetterOrDigit() } || after.none { it.isLetterOrDigit() }) continue
            out += before.trimEnd().trimEnd(',', ';', ':') + ","
            start = m.range.last + 1
        }
        out += text.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private sealed class Part {
        class Audio(val audio: TtsAudio) : Part()
        class Silence(val ms: Int) : Part()
    }

    /**
     * [dialogueRuns] sind die Runs dieses Satzes aus [app.simplenarrator.speech.Dialogue.detect]; sie werden nur
     * mit [SpeechOptions.dialogueVoice] genutzt und nur, wenn sie zusammen den Text des Satzes ergeben
     * (Leerraum zählt nicht), sonst wird [text] als ein Stück gelesen. Regeln wirken dann je Run.
     * Ausnahmen von [synth] (z. B. TtsManager.NoModelException) werden durchgereicht.
     */
    suspend fun render(
        text: String,
        lang: String,
        dialogueRuns: List<Run>?,
        isParagraphEnd: Boolean,
        rules: SpeechRules,
        options: SpeechOptions,
        synth: suspend (text: String, lang: String, dialogue: Boolean) -> TtsAudio,
    ): TtsAudio {
        val runs = if (options.dialogueVoice && dialogueRuns != null && covers(dialogueRuns, text)) dialogueRuns
        else listOf(Run(text, false))

        val parts = ArrayList<Part>()
        if (Segmenter.isSceneBreak(text)) parts += Part.Silence(SCENE_BREAK_MS)
        for (run in runs) {
            for (seg in rules.apply(run.text)) when (seg) {
                is Segment.Pause -> parts += Part.Silence(seg.ms)
                is Segment.Speak -> splitAtEllipsis(seg.text).forEachIndexed { i, piece ->
                    if (i > 0) parts += Part.Silence(ELLIPSIS_PAUSE_MS)
                    val spoken = if (options.normalize) Normalizer.normalize(piece, lang, options.espeak) else piece
                    if (spoken.none { it.isLetterOrDigit() }) return@forEachIndexed
                    val audio = synth(spoken, lang, options.dialogueVoice && run.dialogue)
                    // Zwei Sprechstücke direkt hintereinander: kurze Pause wie bei einem Komma
                    if (parts.lastOrNull() is Part.Audio) parts += Part.Silence(JOIN_GAP_MS)
                    parts += Part.Audio(TtsAudio(Pcm.trimSilence(audio.samples, audio.sampleRate), audio.sampleRate))
                }
            }
        }
        val endMs = if (isParagraphEnd) options.paragraphPauseMs else options.sentencePauseMs
        parts += Part.Silence(endMs.coerceIn(0, SpeechRules.MAX_PAUSE_MS))

        val first = parts.firstNotNullOfOrNull { (it as? Part.Audio)?.audio }
        val rate = first?.sampleRate ?: DEFAULT_SAMPLE_RATE
        val pieces = parts.map { p ->
            when (p) {
                is Part.Audio -> if (p.audio.sampleRate == rate) p.audio.samples else Pcm.resample(p.audio.samples, p.audio.sampleRate, rate)
                is Part.Silence -> FloatArray(samplesFor(p.ms, rate))
            }
        }
        var total = pieces.sumOf { it.size }
        if (first == null) total = maxOf(total, samplesFor(EMPTY_SILENCE_MS, rate))
        val out = FloatArray(total)
        var pos = 0
        for (piece in pieces) {
            piece.copyInto(out, pos)
            pos += piece.size
        }
        return TtsAudio(out, rate)
    }

    internal fun samplesFor(ms: Int, rate: Int): Int = (ms.toLong() * rate / 1000).toInt()

    private fun covers(runs: List<Run>, text: String): Boolean {
        if (runs.isEmpty()) return false
        val joined = runs.joinToString("") { it.text }
        return joined.filterNot { it.isWhitespace() } == text.filterNot { it.isWhitespace() }
    }
}
