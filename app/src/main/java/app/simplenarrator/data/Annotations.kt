package app.simplenarrator.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Lesezeichen an einem Satz; mit [note] ist es eine Notiz. */
@Serializable
data class Annotation(
    val id: String,
    val chapter: Int,
    val sentence: Int,
    val excerpt: String,
    val note: String = "",
    val createdAt: Long,
)

/** Lesezeichen und Notizen je Buch in files/books/<id>/annotations.json. */
class AnnotationStore(private val repo: BookRepository) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val all = MutableStateFlow<Map<String, List<Annotation>>>(emptyMap())

    private fun file(bookId: String) = File(repo.bookDir(bookId), "annotations.json")

    fun flow(bookId: String): Flow<List<Annotation>> = all.map { m -> m[bookId].orEmpty().sortedWith(compareBy({ it.chapter }, { it.sentence })) }

    suspend fun load(bookId: String) = withContext(Dispatchers.IO) {
        if (bookId in all.value) return@withContext
        val list = runCatching { json.decodeFromString<List<Annotation>>(file(bookId).readText()) }.getOrDefault(emptyList())
        all.value = all.value + (bookId to list)
    }

    private suspend fun save(bookId: String, list: List<Annotation>) = withContext(Dispatchers.IO) {
        all.value = all.value + (bookId to list)
        runCatching { file(bookId).writeText(json.encodeToString(list)) }
    }

    fun at(bookId: String, chapter: Int, sentence: Int): Annotation? =
        all.value[bookId]?.firstOrNull { it.chapter == chapter && it.sentence == sentence }

    /** Setzt ein Lesezeichen oder entfernt es; true, wenn danach eines da ist. */
    suspend fun toggle(book: Book, chapter: Int, sentence: Int): Boolean {
        load(book.id)
        val list = all.value[book.id].orEmpty()
        val existing = list.firstOrNull { it.chapter == chapter && it.sentence == sentence }
        return if (existing != null) {
            save(book.id, list - existing); false
        } else {
            add(book, chapter, sentence, ""); true
        }
    }

    /** Lesezeichen setzen, falls an dieser Stelle noch keines ist (etwa „gesprochenen Satz merken“). */
    suspend fun mark(book: Book, chapter: Int, sentence: Int) {
        load(book.id)
        if (at(book.id, chapter, sentence) == null) add(book, chapter, sentence, "")
    }

    suspend fun setNote(book: Book, chapter: Int, sentence: Int, note: String) {
        load(book.id)
        val list = all.value[book.id].orEmpty()
        val existing = list.firstOrNull { it.chapter == chapter && it.sentence == sentence }
        if (existing != null) save(book.id, list.map { if (it.id == existing.id) it.copy(note = note.trim()) else it })
        else add(book, chapter, sentence, note.trim())
    }

    suspend fun delete(bookId: String, id: String) {
        save(bookId, all.value[bookId].orEmpty().filterNot { it.id == id })
    }

    private suspend fun add(book: Book, chapter: Int, sentence: Int, note: String) {
        val excerpt = book.chapters.getOrNull(chapter)?.sentenceAt(sentence)?.text.orEmpty().take(300)
        val a = Annotation(UUID.randomUUID().toString(), chapter, sentence, excerpt, note, System.currentTimeMillis())
        save(book.id, all.value[book.id].orEmpty() + a)
    }

    /** Alle Lesezeichen und Notizen eines Buchs als Text zum Teilen. */
    fun asText(book: Book, list: List<Annotation>, chapterLabel: (Int) -> String): String = buildString {
        append(book.title)
        if (book.author.isNotBlank()) append(" · ").append(book.author)
        append("\n\n")
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (a in list) {
            val ch = book.chapters.getOrNull(a.chapter)
            append(chapterLabel(a.chapter)).append(ch?.title?.let { ": $it" }.orEmpty()).append(" (").append(df.format(Date(a.createdAt))).append(")\n")
            append("» ").append(a.excerpt).append(" «\n")
            if (a.note.isNotBlank()) append(a.note).append('\n')
            append('\n')
        }
    }

    /** Beim Löschen eines Buchs mitlöschen (die Datei liegt im Buchordner). */
    fun forget(bookId: String) { all.value = all.value - bookId }
}
