package app.simplenarrator.tts

import android.content.Context
import app.simplenarrator.data.AppSettings
import app.simplenarrator.data.Book
import app.simplenarrator.speech.Dialogue
import app.simplenarrator.speech.Run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Ein fertig erzeugter Satz als WAV-Datei. [pauseMs] ist die angehängte Stille am Ende. */
class SentenceClip(val file: File, val durationMs: Long, val pauseMs: Int)

/**
 * Erzeugt Satz-Audio für Vorlesen und Export und hält es in einem Zwischenspeicher. Der Schlüssel enthält alles,
 * was den Klang ändert (Stimme, Tempo, Wörterbuch, Pausen, Text); wer zurückspult oder ein Kapitel erneut hört,
 * bekommt die Datei ohne neue Synthese. Ein abgebrochener Export setzt auf denselben Dateien wieder auf.
 */
class SentenceAudio(context: Context, private val tts: TtsManager) {
    private val cacheDir = File(context.cacheDir, "sentences").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "sentences-tmp").apply { mkdirs() }
    private var writesSinceTrim = 0

    /** Satz [sentence] in Kapitel [chapter] als WAV; mit [s].cacheAudio dauerhaft, sonst als Wegwerfdatei. */
    suspend fun clip(book: Book, chapter: Int, sentence: Int, s: AppSettings): SentenceClip {
        val ch = book.chapters[chapter]
        val sent = ch.sentenceAt(sentence) ?: error("Sentence $sentence missing in chapter $chapter")
        val paragraphEnd = ch.endsParagraph(sentence)
        val pause = if (paragraphEnd) s.paragraphPauseMs else s.sentencePauseMs
        val voice = tts.voiceFor(sent.lang, false, s)
        val key = keyOf(sent.text, sent.lang, paragraphEnd, voice, s)
        val dir = if (s.cacheAudio) cacheDir else tempDir
        val file = File(dir, "$key.wav")
        if (file.isFile && file.length() > 44) {
            file.setLastModified(System.currentTimeMillis())
            return SentenceClip(file, WavWriter.durationMs(file), pause)
        }
        val runs = if (s.dialogueVoice) dialogueRuns(book, chapter, sentence) else null
        val audio = tts.renderSentence(sent.text, sent.lang, runs, paragraphEnd, s)
        withContext(Dispatchers.IO) {
            // Android darf den Cache-Ordner jederzeit leeren; deshalb vor jedem Schreiben anlegen
            dir.mkdirs()
            val tmp = File(dir, "$key.part")
            WavWriter.write(tmp, audio)
            tmp.renameTo(file)
            if (s.cacheAudio && ++writesSinceTrim >= 20) { writesSinceTrim = 0; trim(s.cacheLimitMb) }
        }
        return SentenceClip(file, (audio.samples.size * 1000L) / audio.sampleRate, pause)
    }

    /** Wie [clip], aber als Abtastwerte (für den Export). */
    suspend fun samples(book: Book, chapter: Int, sentence: Int, s: AppSettings): TtsAudio {
        val c = clip(book, chapter, sentence, s)
        val audio = withContext(Dispatchers.IO) { WavWriter.read(c.file) }
        if (!s.cacheAudio) c.file.delete()
        return audio
    }

    /** Wörtliche Rede: Runs des Satzes aus seinem Absatz (Anführungszeichen können über Sätze reichen). */
    private val runsCache = object : LinkedHashMap<String, List<List<Run>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<List<Run>>>?) = size > 64
    }

    @Synchronized
    private fun dialogueRuns(book: Book, chapter: Int, sentence: Int): List<Run>? {
        val ch = book.chapters[chapter]
        val (pi, si) = ch.locate(sentence) ?: return null
        val runs = runsCache.getOrPut("${book.id}/$chapter/$pi") { Dialogue.detect(ch.paragraphs[pi].sentences.map { it.text }) }
        return runs.getOrNull(si)
    }

    private fun keyOf(text: String, lang: String, paragraphEnd: Boolean, voice: TtsManager.Voice?, s: AppSettings): String {
        val parts = listOf(
            text, lang, paragraphEnd, voice?.pack?.id, voice?.sid, voice?.voicesFile?.name, s.voiceSpeed, s.autoLang,
            s.normalizeText, s.lexicon, s.sentencePauseMs, s.paragraphPauseMs, s.dialogueVoice, s.dialoguePackDe,
            s.dialogueSidDe, s.dialoguePackEn, s.dialogueSidEn, SherpaTtsEngine.SUPERTONIC_STEPS, VERSION,
        )
        val md = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("\u0001").toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /** Älteste Dateien löschen, bis der Speicher unter [limitMb] liegt. */
    fun trim(limitMb: Int) {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        val limit = limitMb * 1024L * 1024L
        for (f in files) {
            if (total <= limit) break
            total -= f.length()
            f.delete()
        }
    }

    fun clearTemp() { tempDir.listFiles()?.forEach { it.delete() } }

    fun clearAll() { cacheDir.listFiles()?.forEach { it.delete() }; clearTemp() }

    fun sizeBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /** Erhöhen, wenn sich die Erzeugung ändert, damit alte Dateien nicht mehr passen. */
        // 3: Modellstille abgeschnitten, Zögerpause bei Auslassungspunkten, Szenenpause
        private const val VERSION = 3
    }
}

/** Liest Länge und Abtastrate aus dem WAV-Kopf, ohne die Datei zu laden. */
private fun WavWriter.durationMs(file: File): Long = RandomAccessFile(file, "r").use { raf ->
    raf.seek(24)
    val b = ByteArray(4); raf.readFully(b)
    val rate = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    if (rate <= 0) 0L else (file.length() - 44) / 2 * 1000L / rate
}
