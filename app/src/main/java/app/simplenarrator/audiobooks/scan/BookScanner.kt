package app.simplenarrator.audiobooks.scan

import app.simplenarrator.audiobooks.data.Book
import app.simplenarrator.audiobooks.data.CoverStore
import app.simplenarrator.audiobooks.data.LibraryRoot
import app.simplenarrator.audiobooks.data.Mark
import app.simplenarrator.audiobooks.data.Track
import app.simplenarrator.audiobooks.scan.chapters.ChannelSource
import app.simplenarrator.audiobooks.scan.chapters.CueSheet
import app.simplenarrator.audiobooks.scan.chapters.Id3Chapters
import app.simplenarrator.audiobooks.scan.chapters.Mp4Chapters
import app.simplenarrator.audiobooks.scan.chapters.VorbisChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Baut aus einem Bibliotheksordner Bücher: Ordnerbaum lesen, gruppieren, Dateien untersuchen, Kapitel und Titelbild
 * bestimmen. Wie Ordner gelesen und Dateien untersucht werden, liefert die Plattform.
 */
abstract class BookScanner(protected val covers: CoverStore) {

    data class Progress(val done: Int, val total: Int)

    data class Probe(val durationMs: Long, val tags: Tags, val marks: List<Mark>)

    /** Ordnerbaum eines Bibliotheksordners; null, wenn er nicht lesbar ist. */
    protected abstract fun listTree(root: LibraryRoot): FsDir?

    /** Adresse einer Datei, wie sie in [Track.uri] steht und der Player sie öffnet. */
    protected abstract fun fileUri(root: LibraryRoot, file: FsFile): String

    /** Dauer in Millisekunden (0 = unbekannt) und Tags einer Audiodatei. */
    protected abstract fun metadata(uri: String): Pair<Long, Tags>

    /** Führt [block] mit einem Lesekanal auf die Datei aus; null, wenn sie sich nicht öffnen lässt. */
    protected abstract fun <R> withChannel(uri: String, block: (FileChannel) -> R): R?

    /** Dauer auf anderem Weg, wenn Tags und Kapitel keine nennen. */
    protected open fun fallbackDuration(uri: String): Long = 0

    /** Eingebettetes Titelbild einer Audiodatei. */
    protected abstract fun embeddedPicture(uri: String): ByteArray?

    /** Inhalt einer kleineren Datei (Titelbild, Cue); null, wenn sie fehlt oder größer als [maxBytes] ist. */
    protected abstract fun readBytes(root: LibraryRoot, file: FsFile, maxBytes: Int): ByteArray?

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scanRoot(root: LibraryRoot, old: Map<String, Book>, onProgress: (Progress) -> Unit): List<Book>? =
        withContext(Dispatchers.IO) {
            val fs = runCatching { listTree(root) }.getOrNull() ?: return@withContext null
            val candidates = Grouping.group(fs, root.single)
            val done = AtomicInteger(0)
            onProgress(Progress(0, candidates.size))
            val pool = Dispatchers.IO.limitedParallelism(3)
            coroutineScope {
                candidates.map { c ->
                    async(pool) {
                        ensureActive()
                        val book = runCatching { buildBook(root, c, old) }.getOrNull()
                        onProgress(Progress(done.incrementAndGet(), candidates.size))
                        book
                    }
                }.awaitAll().filterNotNull()
            }
        }

    private fun buildBook(root: LibraryRoot, c: Candidate, oldBooks: Map<String, Book>): Book? {
        if (c.audio.isEmpty()) return null
        val id = bookId(root.uri, c.path)
        val old = oldBooks[id]
        val uris = c.audio.map { fileUri(root, it.file) }

        val unchanged = old != null && old.tracks.size == c.audio.size &&
            old.tracks.indices.all { i ->
                val t = old.tracks[i]
                val f = c.audio[i].file
                t.uri == uris[i] && t.size == f.size && t.modified == f.modified && t.path == c.audio[i].path
            }

        var tracks: List<Track>
        val meta: BookMeta
        if (unchanged) {
            tracks = old!!.tracks
            meta = BookMeta(old.title, old.author, old.narrator, Names.plausibleYear(old.year))
        } else {
            val probes = c.audio.mapIndexed { i, ref -> probe(uris[i], ref.file.name) }
            tracks = c.audio.mapIndexed { i, ref ->
                Track(
                    uri = uris[i],
                    path = ref.path,
                    durationMs = probes[i].durationMs,
                    size = ref.file.size,
                    modified = ref.file.modified,
                    title = probes[i].tags.title?.trim()?.takeIf { it.isNotEmpty() },
                    marks = probes[i].marks,
                )
            }
            tracks = applyCue(root, c, tracks)
            meta = BookBuilder.meta(c, probes.map { it.tags })
            if (tracks.all { it.durationMs <= 0 }) return null
        }

        // Titelbild
        val named = Grouping.pickCoverImage(c.images.filter { isPreferredName(it.name) })
        val coverKey: String
        var cover: String? = null
        var color: Int? = null
        // Gültig ist auch ein früheres Ergebnis "kein Titelbild", damit nicht bei jedem Start erneut gesucht wird.
        val oldCoverValid = old != null && (old.cover == null || File(old.cover).exists())
        if (named != null) {
            coverKey = "img:${named.id}:${named.modified}:${named.size}"
            if (oldCoverValid && old!!.coverKey == coverKey) {
                cover = old.cover; color = old.color
            } else {
                saveImage(root, id, named)?.let { cover = it.path; color = it.color }
            }
        } else {
            val first = tracks.first()
            coverKey = "emb:${first.uri}:${first.modified}:${c.images.size}"
            if (oldCoverValid && old!!.coverKey == coverKey) {
                cover = old.cover; color = old.color
            } else {
                val bytes = tracks.asSequence().take(3).mapNotNull { runCatching { embeddedPicture(it.uri) }.getOrNull() }.firstOrNull()
                val saved = (if (bytes != null) covers.saveBytes(id, bytes) else null)
                    ?: Grouping.pickCoverImage(c.images)?.let { img -> saveImage(root, id, img) }
                if (saved != null) { cover = saved.path; color = saved.color }
            }
        }

        return Book(
            id = id,
            root = root.uri,
            path = c.path,
            title = meta.title,
            author = meta.author,
            narrator = meta.narrator,
            year = meta.year,
            tracks = tracks,
            chapters = BookBuilder.chapters(tracks),
            cover = cover,
            coverKey = coverKey,
            color = color,
            addedAt = old?.addedAt ?: System.currentTimeMillis(),
        )
    }

    private fun saveImage(root: LibraryRoot, id: String, image: FsFile) =
        readBytes(root, image, MAX_IMAGE_BYTES)?.let { covers.saveBytes(id, it) }

    private fun isPreferredName(name: String): Boolean {
        val base = name.substringBeforeLast('.').lowercase()
        return listOf("cover", "folder", "front", "albumart", "poster").any { base.startsWith(it) }
    }

    private fun applyCue(root: LibraryRoot, c: Candidate, tracks: List<Track>): List<Track> {
        val cue = c.cues.firstOrNull() ?: return tracks
        val text = readBytes(root, cue, 2 * 1024 * 1024)?.let(::decodeText) ?: return tracks
        val byFile = CueSheet.parse(text)
        if (byFile.isEmpty()) return tracks
        return tracks.map { t ->
            if (t.marks.isNotEmpty()) return@map t
            val marks = byFile.entries.firstOrNull { it.key.equals(t.fileName, ignoreCase = true) }?.value
                ?: byFile.entries.firstOrNull { it.key.substringBeforeLast('.').equals(t.fileName.substringBeforeLast('.'), true) }?.value
                ?: if (tracks.size == 1 && byFile.size == 1) byFile.values.first() else null
            if (marks.isNullOrEmpty()) t else t.copy(marks = marks.filter { it.startMs < t.durationMs || t.durationMs <= 0 })
        }
    }

    // ---------------------------------------------------------------- Dateien untersuchen

    fun probe(uri: String, name: String): Probe {
        var (duration, tags) = runCatching { metadata(uri) }.getOrDefault(0L to Tags())
        var marks = emptyList<Mark>()
        runCatching {
            withChannel(uri) { channel ->
                val src = ChannelSource(channel)
                when (Names.ext(name)) {
                    "m4a", "m4b", "mp4", "aac", "3gp" -> Mp4Chapters.read(src)?.let { res ->
                        marks = res.marks
                        if (duration <= 0) duration = res.durationMs ?: 0
                    }
                    "mp3" -> marks = Id3Chapters.read(src)
                    "ogg", "oga", "opus" -> marks = VorbisChapters.readOgg(src)
                    "flac" -> marks = VorbisChapters.readFlac(src)
                }
            }
        }
        if (duration <= 0) duration = runCatching { fallbackDuration(uri) }.getOrDefault(0L)
        return Probe(duration, tags, marks)
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 30 * 1024 * 1024

        fun bookId(root: String, path: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest("$root\n$path".toByteArray())
            return d.take(10).joinToString("") { "%02x".format(it) }
        }

        /** UTF-8, sonst Windows-1252 (ältere Cue-Dateien). */
        fun decodeText(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
