package app.simplenarrator.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.simplenarrator.R
import app.simplenarrator.util.AppLanguage
import app.simplenarrator.parser.EpubParser
import app.simplenarrator.parser.PdfParser
import app.simplenarrator.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Bibliothek: importierte Bücher liegen als Ordner unter files/books/<id>/
 * mit book.json (Text, kapitelweise), progress.json, cover.* und der Originaldatei.
 */
class BookRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    fun bookDir(id: String): File = File(booksDir, id)

    suspend fun load() = withContext(Dispatchers.IO) {
        val list = mutableListOf<Book>()
        val prog = mutableMapOf<String, Progress>()
        booksDir.listFiles()?.forEach { dir ->
            val f = File(dir, "book.json")
            if (f.isFile) runCatching {
                list += json.decodeFromString<Book>(f.readText())
                val p = File(dir, "progress.json")
                if (p.isFile) prog[dir.name] = json.decodeFromString<Progress>(p.readText())
            }
        }
        _books.value = list.sortedByDescending { prog[it.id]?.updatedAt ?: it.addedAt }
        _progress.value = prog
    }

    fun getBook(id: String): Book? = _books.value.firstOrNull { it.id == id }

    fun coverFile(book: Book): File? = book.coverFile?.let { File(bookDir(book.id), it) }?.takeIf { it.isFile }

    /** Ein PDF besteht überwiegend aus gescannten Seiten; es liegt zwischengelagert unter [stagingId]. */
    class ScannedPdfException(val stagingId: String, val displayName: String, val scanned: Int, val pages: Int) : Exception("scanned PDF")

    private fun stagingDir(id: String) = File(context.filesDir, "import-staging/$id")

    fun stagedPdf(stagingId: String): File = File(stagingDir(stagingId), "source.pdf")

    fun stagedName(stagingId: String): String = runCatching { File(stagingDir(stagingId), "name.txt").readText() }.getOrDefault("book.pdf")

    fun discardStaged(stagingId: String) { stagingDir(stagingId).deleteRecursively() }

    /** Zwischengelagertes PDF übernehmen; [ocr] ersetzt den Text gescannter Seiten (leer: ohne Texterkennung). */
    suspend fun finishPdfImport(stagingId: String, ocr: Map<Int, String>): Book = withContext(Dispatchers.IO) {
        val res = AppLanguage.wrap(context)
        val displayName = stagedName(stagingId)
        val dir = bookDir(stagingId).apply { mkdirs() }
        val source = File(dir, "source.pdf")
        try {
            stagedPdf(stagingId).copyTo(source, overwrite = true)
            val parsed = PdfParser.parse(context, source, ocr)
            if (parsed.chapters.isEmpty()) error(res.getString(R.string.import_error_empty))
            val book = Book(
                id = stagingId,
                title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
                author = parsed.author,
                sourceType = "pdf",
                coverFile = null,
                chapters = parsed.chapters,
                addedAt = System.currentTimeMillis(),
            )
            File(dir, "book.json").writeText(json.encodeToString(book))
            _books.value = listOf(book) + _books.value.filterNot { it.id == stagingId }
            discardStaged(stagingId)
            book
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** [checkScanned]: bei überwiegend gescannten PDFs mit [ScannedPdfException] nachfragen. */
    suspend fun importFromUri(uri: Uri, checkScanned: Boolean = true): Book = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "book.txt"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val id = UUID.randomUUID().toString()
        val dir = bookDir(id).apply { mkdirs() }
        val source = File(dir, "source.$ext")
        val res = AppLanguage.wrap(context)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { out -> input.copyTo(out) }
            } ?: error(res.getString(R.string.import_error_read))

            if (ext == "pdf" && checkScanned) {
                val texts = PdfParser.pageTexts(context, source)
                if (PdfParser.mostlyScanned(texts)) {
                    // Zwischenlagern und nachfragen, ob die Texterkennung laufen soll
                    val staged = stagingDir(id).apply { mkdirs() }
                    source.copyTo(File(staged, "source.pdf"), overwrite = true)
                    File(staged, "name.txt").writeText(displayName)
                    dir.deleteRecursively()
                    throw ScannedPdfException(id, displayName, PdfParser.scannedPages(texts).size, texts.size)
                }
            }
            val parsed: ParsedBook = when (ext) {
                "epub" -> EpubParser.parse(source, dir)
                "pdf" -> PdfParser.parse(context, source)
                "txt", "md" -> TxtParser.parse(source)
                else -> error(res.getString(R.string.import_error_format, ext))
            }
            if (parsed.chapters.isEmpty()) error(res.getString(R.string.import_error_empty))

            val book = Book(
                id = id,
                title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
                author = parsed.author,
                sourceType = ext,
                coverFile = parsed.coverFile,
                chapters = parsed.chapters,
                addedAt = System.currentTimeMillis(),
            )
            File(dir, "book.json").writeText(json.encodeToString(book))
            _books.value = listOf(book) + _books.value
            book
        } catch (e: Throwable) {
            if (e !is ScannedPdfException) dir.deleteRecursively()
            throw e
        }
    }

    /**
     * Bereits zerlegten Text speichern (eingefügter Text, Webartikel). Ablage wie bei importFromUri:
     * neuer Ordner mit book.json und optional cover.*; die Bücherliste wird sofort aktualisiert.
     */
    suspend fun importParsed(
        parsed: ParsedBook,
        sourceType: String,
        fallbackTitle: String,
        sourceUrl: String? = null,
        cover: ByteArray? = null,
    ): Book = withContext(Dispatchers.IO) {
        if (parsed.chapters.isEmpty()) error(AppLanguage.wrap(context).getString(R.string.import_error_empty))
        val id = UUID.randomUUID().toString()
        val dir = bookDir(id).apply { mkdirs() }
        try {
            val coverName = cover?.takeIf { it.size > 16 }?.let { bytes ->
                // Dateiendung nach den ersten Bytes (PNG, WebP, sonst JPEG)
                val ext = when {
                    bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "png"
                    String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
                    else -> "jpg"
                }
                "cover.$ext".also { File(dir, it).writeBytes(bytes) }
            } ?: parsed.coverFile
            val book = Book(
                id = id,
                title = parsed.title.ifBlank { fallbackTitle },
                author = parsed.author,
                sourceType = sourceType,
                coverFile = coverName,
                chapters = parsed.chapters,
                addedAt = System.currentTimeMillis(),
                sourceUrl = sourceUrl,
            )
            File(dir, "book.json").writeText(json.encodeToString(book))
            _books.value = listOf(book) + _books.value
            book
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    suspend fun saveProgress(id: String, chapter: Int, sentence: Int) = withContext(Dispatchers.IO) {
        val p = Progress(chapter, sentence, System.currentTimeMillis())
        _progress.value = _progress.value + (id to p)
        runCatching { File(bookDir(id), "progress.json").writeText(json.encodeToString(p)) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        bookDir(id).deleteRecursively()
        _books.value = _books.value.filterNot { it.id == id }
        _progress.value = _progress.value - id
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }
}
