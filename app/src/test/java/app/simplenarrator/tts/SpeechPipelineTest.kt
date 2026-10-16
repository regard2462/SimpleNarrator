package app.simplenarrator.tts

import app.simplenarrator.speech.Dialogue
import app.simplenarrator.speech.Normalizer
import app.simplenarrator.speech.Run
import app.simplenarrator.speech.SpeechRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ein Satz durch Regeln, Normalisierer, Dialogstimme und Pausen, mit einer Schein-Synthese. */
class SpeechPipelineTest {
    private data class Call(val text: String, val lang: String, val dialogue: Boolean)

    /** Schein-Synthese: 10 Werte pro Zeichen, Erzählstimme 0,25, Dialogstimme 0,5. */
    private class FakeSynth(val narratorRate: Int = 24000, val dialogueRate: Int = 24000) {
        val calls = mutableListOf<Call>()
        val synth: suspend (String, String, Boolean) -> TtsAudio = { text, lang, dialogue ->
            calls += Call(text, lang, dialogue)
            TtsAudio(FloatArray(text.length * 10) { if (dialogue) 0.5f else 0.25f }, if (dialogue) dialogueRate else narratorRate)
        }
    }

    private val plain = SpeechOptions(normalize = false, sentencePauseMs = 100, paragraphPauseMs = 500, dialogueVoice = false)

    private fun render(
        text: String,
        options: SpeechOptions = plain,
        rules: SpeechRules = SpeechRules.EMPTY,
        runs: List<Run>? = null,
        paragraphEnd: Boolean = false,
        fake: FakeSynth = FakeSynth(),
        lang: String = "de",
    ): TtsAudio = runBlocking { SpeechPipeline.render(text, lang, runs, paragraphEnd, rules, options, fake.synth) }

    @Test
    fun einfacherSatzMitSatzpause() {
        val fake = FakeSynth()
        val audio = render("Hallo Welt.", fake = fake)
        assertEquals(listOf(Call("Hallo Welt.", "de", false)), fake.calls)
        assertEquals(24000, audio.sampleRate)
        assertEquals(11 * 10 + 2400, audio.samples.size)
        assertTrue(audio.samples.take(110).all { it == 0.25f })
        assertTrue(audio.samples.drop(110).all { it == 0f })
    }

    @Test
    fun absatzpauseErsetztSatzpause() {
        val audio = render("Hallo Welt.", paragraphEnd = true)
        assertEquals(110 + 12000, audio.samples.size)
    }

    @Test
    fun normalisiererNachDenRegeln() {
        val fake = FakeSynth()
        val opts = plain.copy(normalize = true)
        render("Es kostet 5 €.", options = opts, fake = fake)
        assertEquals(Normalizer.normalize("Es kostet 5 €.", "de"), fake.calls.single().text)
        assertEquals("Es kostet fünf Euro.", fake.calls.single().text)

        val fake2 = FakeSynth()
        render("Es kostet 5 €.", options = opts, rules = SpeechRules.parse("*(\\d+) €=\$1 Taler"), fake = fake2)
        assertEquals("Es kostet fünf Taler.", fake2.calls.single().text)

        val fake3 = FakeSynth()
        render("It costs 5 dollars.", options = opts, fake = fake3, lang = "en")
        assertEquals(Call("It costs five dollars.", "en", false), fake3.calls.single())
    }

    @Test
    fun pausenAusRegelnWerdenStille() {
        val fake = FakeSynth()
        val audio = render("Erst das; dann das.", rules = SpeechRules.parse("*;=[pause:200]"), fake = fake)
        assertEquals(listOf("Erst das", "dann das."), fake.calls.map { it.text })
        val first = 8 * 10
        val pause = 4800
        val second = 9 * 10
        assertEquals(first + pause + second + 2400, audio.samples.size)
        assertTrue(audio.samples.copyOfRange(first, first + pause).all { it == 0f })
        assertTrue(audio.samples.copyOfRange(first + pause, first + pause + second).all { it == 0.25f })
    }

    @Test
    fun pausenImBuchtext() {
        val fake = FakeSynth()
        val audio = render("Er wartete.[pause:1000] Dann ging er.", fake = fake)
        assertEquals(listOf("Er wartete.", "Dann ging er."), fake.calls.map { it.text })
        assertEquals(110 + 24000 + 130 + 2400, audio.samples.size)
    }

    @Test
    fun dialogstimme() {
        val sentence = "„Komm her“, sagte er, „und setz dich.“"
        val runs = Dialogue.detect(sentence)
        val fake = FakeSynth()
        render(sentence, options = plain.copy(dialogueVoice = true), runs = runs, fake = fake)
        assertEquals(listOf(true, false, true), fake.calls.map { it.dialogue })
        assertEquals(listOf("„Komm her“", ", sagte er,", "„und setz dich.“"), fake.calls.map { it.text })

        // Ohne Dialogstimme: ein Aufruf mit dem ganzen Satz
        val fake2 = FakeSynth()
        render(sentence, options = plain, runs = runs, fake = fake2)
        assertEquals(listOf(Call(sentence, "de", false)), fake2.calls)
    }

    @Test
    fun unpassendeRunsWerdenIgnoriert() {
        val fake = FakeSynth()
        render("Ein anderer Satz.", options = plain.copy(dialogueVoice = true), runs = listOf(Run("„Hallo“", true)), fake = fake)
        assertEquals(listOf(Call("Ein anderer Satz.", "de", false)), fake.calls)
    }

    @Test
    fun abtastrateDesErstenTeils() {
        val sentence = "„Ja“, sagte sie leise."
        val fake = FakeSynth(narratorRate = 24000, dialogueRate = 22050)
        val audio = render(sentence, options = plain.copy(dialogueVoice = true), runs = Dialogue.detect(sentence), fake = fake)
        assertEquals(22050, audio.sampleRate)
        val narrator = Pcm.resample(FloatArray(", sagte sie leise.".length * 10), 24000, 22050).size
        val gap = SpeechPipeline.samplesFor(SpeechPipeline.JOIN_GAP_MS, 22050)
        assertEquals(4 * 10 + gap + narrator + 2205, audio.samples.size)
    }

    @Test
    fun leererTextNachRegelnGibtStille() {
        val fake = FakeSynth()
        val audio = render("Seite 12", rules = SpeechRules.parse("*^Seite \\d+$="), options = plain.copy(sentencePauseMs = 0), fake = fake)
        assertTrue(fake.calls.isEmpty())
        assertEquals(SpeechPipeline.DEFAULT_SAMPLE_RATE, audio.sampleRate)
        assertEquals(2400, audio.samples.size)   // 100 ms
        assertTrue(audio.samples.all { it == 0f })

        val withPause = render("Seite 12", rules = SpeechRules.parse("*^Seite \\d+$="), fake = FakeSynth())
        assertEquals(2400, withPause.samples.size)   // Satzpause 100 ms

        val onlyPunct = FakeSynth()
        render("* * *", fake = onlyPunct)
        assertTrue(onlyPunct.calls.isEmpty())
    }

    @Test
    fun modellstilleWirdAbgeschnitten() {
        // 100 ms Stille, 100 ms Sprache, 200 ms Stille, wie ein Modell sie um einen Satz legt
        val synth: suspend (String, String, Boolean) -> TtsAudio = { _, _, _ ->
            TtsAudio(FloatArray(9600) { i -> if (i in 2400 until 4800) 0.25f else 0f }, 24000)
        }
        val audio = runBlocking { SpeechPipeline.render("Hallo.", "de", null, false, SpeechRules.EMPTY, plain, synth) }
        // Rand: 30 ms vorn (720), 60 ms hinten (1440); dann genau die Satzpause von 100 ms
        assertEquals(720 + 2400 + 1440 + 2400, audio.samples.size)
        assertEquals(0f, audio.samples[0])
        assertTrue(audio.samples.drop(720 + 2400 + 1440).all { it == 0f })
    }

    @Test
    fun auslassungspunkteWerdenZoegerpause() {
        val fake = FakeSynth()
        val audio = render("Ich… ich weiß nicht.", fake = fake)
        assertEquals(listOf("Ich,", "ich weiß nicht."), fake.calls.map { it.text })
        val pause = SpeechPipeline.samplesFor(SpeechPipeline.ELLIPSIS_PAUSE_MS, 24000)
        assertEquals(40 + pause + 150 + 2400, audio.samples.size)

        // Am Satzende oder am Anfang bleibt der Text, wie er ist
        val end = FakeSynth()
        render("Und dann...", fake = end)
        assertEquals(listOf("Und dann..."), end.calls.map { it.text })
        val start = FakeSynth()
        render("… und weiter.", fake = start)
        assertEquals(listOf("… und weiter."), start.calls.map { it.text })
        assertEquals(listOf("I,", "I don't know."), SpeechPipeline.splitAtEllipsis("I . . . I don't know."))
    }

    @Test
    fun trennzeileWirdSzenenpause() {
        val fake = FakeSynth()
        val audio = render("* * *", paragraphEnd = true, fake = fake)
        assertTrue(fake.calls.isEmpty())
        assertEquals(SpeechPipeline.samplesFor(SpeechPipeline.SCENE_BREAK_MS, 24000) + 12000, audio.samples.size)
        assertTrue(audio.samples.all { it == 0f })
    }

    @Test
    fun pausenWerdenBegrenzt() {
        val audio = render("Hallo.", options = plain.copy(sentencePauseMs = 60000))
        assertEquals(60 + 5000 * 24, audio.samples.size)
        val negative = render("Hallo.", options = plain.copy(sentencePauseMs = -5))
        assertEquals(60, negative.samples.size)
    }

    @Test
    fun fehlerDerSyntheseWerdenDurchgereicht() {
        val failing: suspend (String, String, Boolean) -> TtsAudio = { _, _, _ -> throw TtsManager.NoModelException() }
        var thrown: Throwable? = null
        try {
            runBlocking { SpeechPipeline.render("Hallo.", "de", null, false, SpeechRules.EMPTY, plain, failing) }
        } catch (e: TtsManager.NoModelException) {
            thrown = e
        }
        assertTrue(thrown is TtsManager.NoModelException)
    }
}
