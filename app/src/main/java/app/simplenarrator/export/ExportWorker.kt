package app.simplenarrator.export

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.simplenarrator.R
import app.simplenarrator.SimpleNarratorApp
import app.simplenarrator.data.AppSettings
import app.simplenarrator.data.Book
import app.simplenarrator.tts.Pcm
import app.simplenarrator.tts.TtsManager
import app.simplenarrator.tts.WavWriter
import app.simplenarrator.util.AppLanguage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Zeitmarken eines fertig erzeugten Kapitels (für Kapitelmarken, Untertitel und das synchrone EPUB). */
@Serializable
private data class ChapterTimes(val sampleRate: Int, val samples: Long, val sentences: List<SentenceTime>)

@Serializable
private data class SentenceTime(val paragraph: Int, val text: String, val startMs: Long, val endMs: Long)

/**
 * Ganzes Buch (oder Kapitelbereich) als Hörbuch erzeugen. Läuft als Vordergrund-Worker mit Fortschritt, überlebt
 * App-Wechsel und legt das Ergebnis in Downloads/Simple Narrator/<Buch>/ ab.
 *
 * Fortsetzbar: Jedes fertige Kapitel liegt als PCM mit Zeitmarken in einem Arbeitsordner, der zu Buch, Bereich,
 * Stimme und Pausen passt. Bricht der Export ab (Neustart, Akku), fängt der nächste Lauf beim ersten fehlenden
 * Kapitel an; schon erzeugte Sätze kommen außerdem aus dem Satz-Zwischenspeicher.
 */
class ExportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_BOOK = "book"; const val KEY_FROM = "from"; const val KEY_TO = "to"
        const val KEY_FORMAT = "format"; const val KEY_SINGLE = "single"
        const val KEY_SUBTITLES = "subtitles"; const val KEY_SYNC_EPUB = "syncEpub"
        const val PROGRESS = "progress"; const val LABEL = "label"
        /** Relativer Ordner unter Downloads, in dem das Ergebnis liegt (für „zu den Hörbüchern“). */
        const val OUT_FOLDER = "folder"
        const val CHANNEL = "export"
        /** Ordner unter Downloads; bewusst unübersetzt, damit er in jeder Sprache gleich bleibt. */
        const val FOLDER = "Simple Narrator"
        private const val NOTIF_ID = 4711
        private const val TAG = "ExportWorker"

        fun workName(bookId: String) = "export-$bookId"

        fun enqueue(context: Context, bookId: String, from: Int, to: Int, format: ExportFormat, singleFile: Boolean, subtitles: Boolean, syncEpub: Boolean) {
            val req = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(
                    KEY_BOOK to bookId, KEY_FROM to from, KEY_TO to to, KEY_FORMAT to format.name,
                    KEY_SINGLE to (singleFile || format == ExportFormat.M4B), KEY_SUBTITLES to subtitles, KEY_SYNC_EPUB to syncEpub,
                ))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context, bookId: String) = WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))

        /** Liste der zuletzt exportierten Dateien eines Buchs (content://-Adressen, eine pro Zeile). */
        fun resultFile(context: Context, bookId: String) = File(context.filesDir, "export-results/$bookId.txt")
    }

    /** Texte in der App-Sprache (vor Android 13 kennt nur dieser Kontext die Wahl). */
    private val res by lazy { AppLanguage.wrap(applicationContext) }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val container = (applicationContext as SimpleNarratorApp).container
        val bookId = inputData.getString(KEY_BOOK) ?: return Result.failure()
        if (container.repo.books.value.isEmpty()) container.repo.load()
        val book = container.repo.getBook(bookId) ?: return fail(res.getString(R.string.book_not_found))
        val from = inputData.getInt(KEY_FROM, 0).coerceIn(0, book.chapters.lastIndex)
        val to = inputData.getInt(KEY_TO, book.chapters.lastIndex).coerceIn(from, book.chapters.lastIndex)
        val format = runCatching { ExportFormat.valueOf(inputData.getString(KEY_FORMAT) ?: "MP3") }.getOrDefault(ExportFormat.MP3)
        val single = inputData.getBoolean(KEY_SINGLE, false) || format == ExportFormat.M4B
        val subtitles = inputData.getBoolean(KEY_SUBTITLES, false)
        val syncEpub = inputData.getBoolean(KEY_SYNC_EPUB, false)
        val s = container.settings.current()

        setForeground(foregroundInfo(book.title, res.getString(R.string.export_starting), 0))
        val work = workDir(book, from, to, s)
        val total = (from..to).sumOf { book.chapters[it].sentenceCount }.coerceAtLeast(1)
        var done = (from..to).filter { File(work, "c$it.json").isFile }.sumOf { book.chapters[it].sentenceCount }
        val outDir = File(applicationContext.cacheDir, "export-out-$bookId").apply { deleteRecursively(); mkdirs() }

        try {
            // 1. Kapitel erzeugen (fertige überspringen)
            var rate = File(work, "rate").takeIf { it.isFile }?.readText()?.toIntOrNull() ?: 0
            for (ci in from..to) {
                if (File(work, "c$ci.json").isFile) continue
                val chapter = book.chapters[ci]
                val pcm = File(work, "c$ci.pcm").apply { delete() }
                val times = ArrayList<SentenceTime>()
                var samples = 0L
                FileOutputStream(pcm).buffered().use { out ->
                    var si = 0
                    chapter.paragraphs.forEachIndexed { pi, p ->
                        for (sentence in p.sentences) {
                            if (isStopped) return fail(res.getString(R.string.export_cancelled))
                            val audio = try {
                                container.audio.samples(book, ci, si, s)
                            } catch (e: TtsManager.NoModelException) {
                                return fail(res.getString(R.string.no_voice_installed))
                            }
                            if (rate == 0) { rate = audio.sampleRate; File(work, "rate").writeText(rate.toString()) }
                            val data = if (audio.sampleRate == rate) audio.samples else Pcm.resample(audio.samples, audio.sampleRate, rate)
                            val pauseMs = if (chapter.endsParagraph(si)) s.paragraphPauseMs else s.sentencePauseMs
                            val startMs = samples * 1000 / rate
                            val endMs = ((samples + data.size) * 1000 / rate - pauseMs).coerceAtLeast(startMs)
                            times += SentenceTime(pi, sentence.text, startMs, endMs)
                            out.write(Pcm.toInt16(data))
                            samples += data.size
                            done++; si++
                            if (done % 5 == 0) {
                                val pct = done * 100 / total
                                val label = res.getString(R.string.export_progress, ci + 1, book.chapters.size, si, chapter.sentenceCount)
                                setProgress(workDataOf(PROGRESS to pct, LABEL to label))
                                setForeground(foregroundInfo(book.title, label, pct))
                            }
                        }
                    }
                }
                File(work, "c$ci.json").writeText(json.encodeToString(ChapterTimes(rate, samples, times)))
            }
            if (rate == 0) rate = 24000

            // 2. Kodieren und beschriften
            val meta = AudioMeta(book.title, book.author, container.repo.coverFile(book)?.readBytes(), container.repo.coverFile(book)?.let { mimeOfImage(it) })
            val chapterTimes = (from..to).associateWith { json.decodeFromString<ChapterTimes>(File(work, "c$it.json").readText()) }
            val bitrate = s.exportBitrate
            val outputs = mutableListOf<File>()
            val syncChapters = mutableListOf<SyncChapter>()

            if (single) {
                setProgress(workDataOf(PROGRESS to 99, LABEL to res.getString(R.string.export_encoding_book)))
                val pcm = File(outDir, "book.pcm")
                val marks = ArrayList<ChapterMark>()
                val cues = ArrayList<TextCue>()
                val pauseSamples = rate.toLong() * s.chapterPauseMs / 1000
                var pos = 0L
                FileOutputStream(pcm).buffered().use { out ->
                    for (ci in from..to) {
                        val t = chapterTimes.getValue(ci)
                        val startMs = pos * 1000 / rate
                        File(work, "c$ci.pcm").inputStream().buffered().use { it.copyTo(out) }
                        pos += t.samples
                        t.sentences.forEach { cues += TextCue(it.text, startMs + it.startMs, startMs + it.endMs) }
                        if (ci < to && pauseSamples > 0) { out.write(ByteArray((pauseSamples * 2).toInt())); pos += pauseSamples }
                        marks += ChapterMark(book.chapters[ci].title, startMs, pos * 1000 / rate)
                    }
                }
                val base = MediaStoreSaver.safeName(book.title)
                val audio = encode(pcm, rate, format, base, bitrate, outDir)
                tag(audio, format, meta, marks)
                outputs += audio
                pcm.delete()
                if (subtitles) outputs += writeSubtitles(outDir, base, cues, book)
                outputs += File(outDir, res.getString(R.string.export_chapter_list_file)).apply { writeText(chapterListing(marks)) }
                if (syncEpub) syncChapters += SyncChapter(book.title, listOf(cues), audio, format.mime)
            } else {
                for (ci in from..to) {
                    if (isStopped) return fail(res.getString(R.string.export_cancelled))
                    setProgress(workDataOf(PROGRESS to (done * 100 / total).coerceAtMost(99), LABEL to res.getString(R.string.export_encoding_chapter, ci + 1)))
                    val t = chapterTimes.getValue(ci)
                    val chapter = book.chapters[ci]
                    val base = String.format(Locale.ROOT, "%02d - %s", ci + 1, MediaStoreSaver.safeName(chapter.title))
                    val audio = encode(File(work, "c$ci.pcm"), rate, format, base, bitrate, outDir)
                    val durMs = t.samples * 1000 / rate
                    tag(audio, format, AudioMeta(chapter.title, book.author, meta.cover, meta.coverMime, album = book.title), listOf(ChapterMark(chapter.title, 0, durMs)))
                    outputs += audio
                    val cues = t.sentences.map { TextCue(it.text, it.startMs, it.endMs) }
                    if (subtitles) outputs += writeSubtitles(outDir, base, cues, book)
                    if (syncEpub) {
                        val paragraphs = t.sentences.groupBy { it.paragraph }.toSortedMap().values.map { list -> list.map { TextCue(it.text, it.startMs, it.endMs) } }
                        syncChapters += SyncChapter(chapter.title, paragraphs, audio, format.mime)
                    }
                }
            }
            if (syncEpub && syncChapters.isNotEmpty()) {
                val epub = File(outDir, MediaStoreSaver.safeName(book.title) + " (Text + Audio).epub")
                val lang = book.chapters.firstOrNull()?.paragraphs?.firstOrNull()?.sentences?.firstOrNull()?.lang ?: "de"
                SyncEpub.write(epub, meta, lang, syncChapters, identifier = book.id)
                outputs += epub
            }

            // 3. Nach Downloads/Simple Narrator/<Buch>/ kopieren
            val folder = "$FOLDER/" + MediaStoreSaver.safeName(book.title)
            val saved = outputs.map { f -> MediaStoreSaver.saveToDownloads(applicationContext, f, f.name, folder) }
            // Die gespeicherten Dateien (für den Upload zu Audiobookshelf); Data von WorkManager ist dafür zu klein
            resultFile(applicationContext, bookId).apply { parentFile?.mkdirs() }.writeText(saved.joinToString("\n"))
            work.deleteRecursively()
            return Result.success(workDataOf(
                PROGRESS to 100, OUT_FOLDER to folder,
                LABEL to res.resources.getQuantityString(R.plurals.export_done, outputs.size, outputs.size, folder),
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Export fehlgeschlagen", e)
            return fail(e.message ?: res.getString(R.string.export_failed))
        } finally {
            outDir.deleteRecursively()
        }
    }

    /** Arbeitsordner: hängt an allem, was den Klang ändert, damit ein Neustart nur passende Kapitel übernimmt. */
    private fun workDir(book: Book, from: Int, to: Int, s: AppSettings): File {
        val key = listOf(book.id, from, to, s.packDe, s.sidDe, s.blendDe, s.packEn, s.sidEn, s.blendEn, s.voiceSpeed, s.lexicon,
            s.normalizeText, s.sentencePauseMs, s.paragraphPauseMs, s.dialogueVoice, s.dialoguePackDe, s.dialogueSidDe,
            s.dialoguePackEn, s.dialogueSidEn, s.autoLang).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val root = File(applicationContext.filesDir, "export-work")
        // Andere Arbeitsordner desselben Buchs sind veraltet
        root.listFiles { f -> f.name.startsWith(book.id) && f.name != "${book.id}-$hash" }?.forEach { it.deleteRecursively() }
        return File(root, "${book.id}-$hash").apply { mkdirs() }
    }

    private fun encode(pcm: File, sampleRate: Int, format: ExportFormat, base: String, bitrateKbps: Int, dir: File): File {
        val out = File(dir, "$base.${format.ext}")
        when (format) {
            ExportFormat.M4A, ExportFormat.M4B -> AacEncoder.encode(pcm, sampleRate, out, bitrateKbps * 1000)
            ExportFormat.MP3 -> Mp3Encoder.encode(pcm, sampleRate, out, bitrateKbps)
            ExportFormat.WAV -> WavWriter.fromPcmFile(pcm, sampleRate, out)
        }
        return out
    }

    /** Titel, Autor, Cover und Kapitelmarken in die Datei schreiben (WAV bleibt ohne). */
    private fun tag(file: File, format: ExportFormat, meta: AudioMeta, marks: List<ChapterMark>) {
        runCatching {
            when (format) {
                ExportFormat.MP3 -> Id3Tag.write(file, meta, marks)
                ExportFormat.M4A, ExportFormat.M4B -> Mp4Tags.write(file, meta, marks)
                ExportFormat.WAV -> Unit
            }
        }.onFailure { Log.w(TAG, "Tags für ${file.name} nicht geschrieben", it) }
    }

    private fun writeSubtitles(dir: File, base: String, cues: List<TextCue>, book: Book): List<File> = listOf(
        File(dir, "$base.srt").apply { writeText(Subtitles.srt(cues)) },
        File(dir, "$base.lrc").apply { writeText(Subtitles.lrc(cues, book.title, book.author)) },
    )

    private fun mimeOfImage(f: File) = if (f.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"

    /** Kapitelmarken als lesbare Liste + FFMETADATA-Block (für ffmpeg). */
    private fun chapterListing(marks: List<ChapterMark>): String {
        val sb = StringBuilder()
        marks.forEach { m ->
            val sec = m.startMs / 1000
            sb.append(String.format(Locale.ROOT, "%02d:%02d:%02d  %s%n", sec / 3600, (sec % 3600) / 60, sec % 60, m.title))
        }
        sb.append("\n;FFMETADATA1\n")
        marks.forEach { m -> sb.append("[CHAPTER]\nTIMEBASE=1/1000\nSTART=${m.startMs}\nEND=${m.endMs}\ntitle=${m.title}\n") }
        return sb.toString()
    }

    private fun fail(msg: String) = Result.failure(workDataOf(LABEL to msg))

    private fun foregroundInfo(title: String, text: String, pct: Int): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(res.getString(R.string.export_notification_title, title))
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }
}
