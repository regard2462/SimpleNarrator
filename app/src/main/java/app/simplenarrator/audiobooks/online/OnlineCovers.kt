package app.simplenarrator.audiobooks.online

import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.data.Library
import app.simplenarrator.audiobooks.data.SavedCover
import app.simplenarrator.audiobooks.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * Lädt Titelbilder für Hörbücher ohne eigenes Bild aus Audible, Apple Books und Open Library und speichert sie
 * im App-Speicher. Läuft nur, wenn die Einstellung an ist; ohne Netz wird beim nächsten Anlass erneut versucht.
 */
class OnlineCovers(
    private val library: Library,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    userAgent: String,
) {
    data class Progress(val done: Int, val total: Int)

    sealed interface SearchResult {
        data class Found(val candidates: List<CoverCandidate>) : SearchResult
        data object Offline : SearchResult
    }

    private val http = Http(userAgent)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val _progress = MutableStateFlow<Progress?>(null)

    /** Fortschritt der laufenden Suche, null wenn keine läuft. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    fun start() {
        scope.launch(Dispatchers.IO) {
            for (k in kick) {
                delay(1_500)
                runCatching { runPass() }
            }
        }
        scope.launch {
            combine(
                settings.flow.map { it.webCovers }.distinctUntilChanged(),
                library.scan.map { it == null }.distinctUntilChanged(),
                library.entries.map { list -> list.filter { needsLookup(it) }.map { it.id }.toSet() }.distinctUntilChanged(),
            ) { on, idle, pending -> on && idle && pending.isNotEmpty() }
                .collect { if (it) kick.trySend(Unit) }
        }
    }

    private fun queryKey(e: Entry) = CoverMatch.normalize(e.title) + "|" + CoverMatch.normalize(e.author.orEmpty())

    /** Braucht das Buch eine (neue) Suche: kein eigenes Bild, nicht gesperrt, Titel geändert oder lange nicht gesucht. */
    private fun needsLookup(e: Entry, now: Long = System.currentTimeMillis()): Boolean {
        val s = e.state
        if (e.book.cover != null || s.customCover != null || s.webBlocked) return false
        if (s.webQuery != queryKey(e)) return true
        return s.webCover == null && now - s.webTriedAt > RETRY_MS
    }

    private suspend fun runPass() {
        val now = System.currentTimeMillis()
        val todo = library.currentEntries()
            .filter { needsLookup(it, now) }
            .sortedWith(compareByDescending<Entry> { it.state.lastPlayedAt }.thenByDescending { it.book.addedAt })
        if (todo.isEmpty()) return
        _progress.value = Progress(0, todo.size)
        try {
            for ((i, old) in todo.withIndex()) {
                if (!settings.value.webCovers) return
                val e = library.entry(old.id)?.takeIf { needsLookup(it, now) } ?: continue
                val saved = try {
                    lookup(e)
                } catch (_: IOException) {
                    return // kein Netz oder Katalog überlastet: beim nächsten Anlass weiter
                }
                if (!settings.value.webCovers) {
                    saved?.let { java.io.File(it.path).delete() }
                    return
                }
                library.setWebCover(e.id, saved, queryKey(e), System.currentTimeMillis())
                _progress.value = Progress(i + 1, todo.size)
            }
        } finally {
            _progress.value = null
        }
    }

    private fun market() = Market.forLanguage(Locale.getDefault().language)

    /** Sucht der Reihe nach in den Katalogen und speichert das erste eindeutig passende Bild. */
    private suspend fun lookup(e: Entry): SavedCover? {
        val market = market()
        val title = e.title
        val author = e.author
        val words = listOfNotNull(title, author).joinToString(" ")
        val sources: List<suspend () -> List<CoverCandidate>> = listOf(
            { http.text(CoverSources.audibleUrl(market, title, author))?.let(CoverSources::parseAudible).orEmpty() },
            { http.text(CoverSources.appleUrl(market, words))?.let(CoverSources::parseApple).orEmpty() },
            { http.text(CoverSources.openLibraryUrl(words))?.let(CoverSources::parseOpenLibrary).orEmpty() },
        )
        for (source in sources) {
            val best = CoverMatch.best(title, author, source()) ?: continue
            val bytes = http.get(best.imageUrl) ?: http.get(best.previewUrl) ?: continue
            library.covers.saveBytes("${e.id}-w${System.currentTimeMillis()}", bytes)?.let { return it }
        }
        return null
    }

    /** Freie Suche für die Auswahl von Hand, alle drei Kataloge gleichzeitig. */
    suspend fun search(query: String, title: String, author: String?): SearchResult = coroutineScope {
        val market = market()
        val calls = listOf(
            async { runCatching { http.text(CoverSources.audibleSearchUrl(market, query))?.let(CoverSources::parseAudible).orEmpty() } },
            async { runCatching { http.text(CoverSources.appleUrl(market, query, 20))?.let(CoverSources::parseApple).orEmpty() } },
            async { runCatching { http.text(CoverSources.openLibraryUrl(query, 20))?.let(CoverSources::parseOpenLibrary).orEmpty() } },
        ).map { it.await() }
        if (calls.all { it.isFailure }) return@coroutineScope SearchResult.Offline
        val all = calls.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.imageUrl }
        SearchResult.Found(CoverMatch.rank(title, author, all))
    }

    suspend fun download(c: CoverCandidate): ByteArray? =
        runCatching { http.get(c.imageUrl) ?: http.get(c.previewUrl) }.getOrNull()

    companion object {
        /** Erneuter Versuch für Bücher ohne Treffer nach 30 Tagen. */
        private const val RETRY_MS = 30L * 24 * 3600 * 1000
    }
}
